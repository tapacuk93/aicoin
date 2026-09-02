package com.aicoin.proxy;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bearer notes, per CONTRACT.md's "Offline notes": a wallet turns balance into signed strings while
 * it has a network, and those strings change hands later with no network on either side.
 *
 * <p>The wallet preloads: one request asks for several notes at once, in the denominations it wants
 * to be able to pay in, and the coins for all of them leave the balance there and then. From that
 * point the sender needs nothing — handing over the string is the payment — and the receiver needs
 * nothing either, because the ledger's signature over the note is checkable with a public key their
 * app cached the last time it was online.
 *
 * <p>What neither side can establish offline is that the note has not already been given to someone
 * else. That is a fact about the ledger and the ledger is not there. Redemption is therefore
 * first-come and atomic, and the second person to try is told plainly. The design keeps the damage
 * bounded rather than pretending to prevent it: the issuer cannot double-spend, because the coins
 * left at issue; only a holder passing the same note to two people can, and each note names its
 * issuer.
 */
final class NoteHandler {

    /** How long a note stays redeemable by default. Long enough to be useful, short enough to end. */
    private static final long DEFAULT_TTL_SECONDS = 30L * 24 * 60 * 60;
    private static final long MAX_TTL_SECONDS = 365L * 24 * 60 * 60;
    /** Most notes one preload may mint. A purse, not a printing press. */
    private static final int MAX_NOTES_PER_REQUEST = 50;

    private NoteHandler() {
    }

    /** {@code GET /wallet/api/notes/key} — the public key a receiver verifies notes with, offline. */
    static void serveKey(ChannelHandlerContext ctx, AicoinLedger ledger) {
        NoteSigner.ensure(ledger, signer -> {
            if (!signer.isPresent()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "note signing is unavailable");
                return;
            }
            sendJson(ctx, "{\"public_key\":\"" + signer.get().publicKeyHex() + "\",\"algorithm\":\"ed25519\"}");
        });
    }

    /**
     * {@code POST /wallet/api/notes/issue} — live-signed. Body {@code {"amounts":[25,10,10,5]}}, or
     * {@code {"amount":25}} for one. The coins leave the wallet now; the notes are returned once and
     * are not recoverable from the server, which is what makes them bearer instruments.
     */
    static void serveIssue(ChannelHandlerContext ctx, byte[] body, AicoinLedger ledger, String issuer) {
        List<Double> amounts = amounts(new String(body, CharsetUtil.UTF_8));
        if (amounts.isEmpty()) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "body must carry amount or amounts");
            return;
        }
        if (amounts.size() > MAX_NOTES_PER_REQUEST) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "at most " + MAX_NOTES_PER_REQUEST + " notes per request");
            return;
        }
        for (double amount : amounts) {
            if (!(amount > 0)) {
                sendError(ctx, HttpResponseStatus.BAD_REQUEST, "every amount must be positive");
                return;
            }
        }
        long ttl = ttlSeconds(new String(body, CharsetUtil.UTF_8));
        long expiresAt = Instant.now().getEpochSecond() + ttl;
        // A note made out to somebody is the one shape of this that cannot be double-spent: hand it
        // to two people and only the named one can redeem it, so the second is holding nothing
        // rather than holding a race. It costs foreknowledge of who is being paid.
        String payee = field(new String(body, CharsetUtil.UTF_8), "payee");
        if (payee != null && !payee.matches("[0-9a-fA-F]{64}")) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "payee must be a 64-character address");
            return;
        }

        NoteSigner.ensure(ledger, signer -> {
            if (!signer.isPresent()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "note signing is unavailable");
                return;
            }
            issueNext(ctx, ledger, signer.get(), issuer, amounts, expiresAt,
                    payee == null ? "" : payee.toLowerCase(java.util.Locale.ROOT), new ArrayList<>());
        });
    }

    /**
     * Issues the notes one at a time, because each is its own atomic debit. A failure part-way
     * through leaves the notes already minted valid and in the response — they are the wallet's,
     * and reclaimable — rather than pretending nothing happened.
     */
    private static void issueNext(ChannelHandlerContext ctx, AicoinLedger ledger, NoteSigner signer,
                                   String issuer, List<Double> remaining, long expiresAt, String payee,
                                   List<String> minted) {
        if (remaining.isEmpty()) {
            sendIssued(ctx, minted, null);
            return;
        }
        double amount = remaining.get(0);
        List<Double> rest = remaining.subList(1, remaining.size());
        Note note = Note.mint(amount, issuer, expiresAt, payee);
        ledger.issueNote(issuer, amount, note.hash(), expiresAt, payee, result -> {
            if (!result.isReachable()) {
                sendIssued(ctx, minted, "could not reach the ledger");
                return;
            }
            if (!result.isOk()) {
                sendIssued(ctx, minted, "insufficient".equals(result.getDetail())
                        ? "not enough balance for the rest" : result.getDetail());
                return;
            }
            try {
                minted.add("{\"note\":\"" + note.encode(signer.sign(note.encodedPayload())) + "\""
                        + ",\"amount\":" + Note.formatAmount(amount)
                        + ",\"fingerprint\":\"" + note.fingerprint() + "\""
                        + ",\"hash\":\"" + note.hash() + "\""
                        + ",\"expires_at\":" + expiresAt
                        + ",\"payee\":\"" + payee + "\"}");
            } catch (Exception e) {
                sendIssued(ctx, minted, "could not sign the note");
                return;
            }
            issueNext(ctx, ledger, signer, issuer, rest, expiresAt, payee, minted);
        });
    }

    private static void sendIssued(ChannelHandlerContext ctx, List<String> minted, String error) {
        StringBuilder json = new StringBuilder("{\"notes\":[").append(String.join(",", minted)).append("]");
        if (error != null) {
            json.append(",\"error\":\"").append(error).append("\"");
        }
        json.append("}");
        sendJson(ctx, json.toString());
    }

    /** {@code POST /wallet/api/notes/redeem} — live-signed. Body {@code {"note":"<the string>"}}. */
    static void serveRedeem(ChannelHandlerContext ctx, byte[] body, AicoinLedger ledger, String holder) {
        Optional<Note> note = Note.decode(field(new String(body, CharsetUtil.UTF_8), "note"));
        if (!note.isPresent()) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "body must carry a note");
            return;
        }
        ledger.redeemNote(holder, note.get().hash(), result -> {
            if (!result.isReachable()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not reach the ledger");
                return;
            }
            if (!result.isOk()) {
                // Already redeemed is the case worth being clear about: somebody was handed this
                // note twice, and the person reading this is the one who did not get the coins.
                // not_payee is the other: this note was made out to somebody else, and no race was
                // ever winnable.
                sendJson(ctx, "{\"credited\":false,\"reason\":\"" + result.getDetail() + "\"}");
                return;
            }
            sendJson(ctx, "{\"credited\":true,\"amount\":" + Note.formatAmount(note.get().getAmount())
                    + ",\"balance\":" + result.getValue() + "}");
        });
    }

    /** {@code POST /wallet/api/notes/reclaim} — live-signed by the issuer, for a note nobody took. */
    static void serveReclaim(ChannelHandlerContext ctx, byte[] body, AicoinLedger ledger, String issuer) {
        Optional<Note> note = Note.decode(field(new String(body, CharsetUtil.UTF_8), "note"));
        if (!note.isPresent()) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "body must carry a note");
            return;
        }
        ledger.reclaimNote(issuer, note.get().hash(), result -> {
            if (!result.isReachable()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not reach the ledger");
                return;
            }
            if (!result.isOk()) {
                sendJson(ctx, "{\"reclaimed\":false,\"reason\":\"" + result.getDetail() + "\"}");
                return;
            }
            sendJson(ctx, "{\"reclaimed\":true,\"amount\":" + Note.formatAmount(note.get().getAmount())
                    + ",\"balance\":" + result.getValue() + "}");
        });
    }

    /**
     * {@code GET /wallet/api/notes/status/{hash}} — is this note still open? By hash, not by note:
     * asking after one should not require handing the secret to anybody, including this server.
     */
    static void serveStatus(ChannelHandlerContext ctx, AicoinLedger ledger, String noteHash) {
        if (!noteHash.matches("[0-9a-fA-F]{64}")) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "hash must be 64 hex characters");
            return;
        }
        ledger.noteState(noteHash.toLowerCase(java.util.Locale.ROOT), state -> {
            if (!state.isPresent()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not reach the ledger");
                return;
            }
            Map<String, String> fields = state.get();
            if (fields.isEmpty()) {
                // Expired notes are deleted by Redis itself, so "unknown" covers both never-issued
                // and long-expired; neither can be redeemed.
                sendJson(ctx, "{\"state\":\"unknown\"}");
                return;
            }
            sendJson(ctx, "{\"state\":\"" + fields.getOrDefault("state", "unknown")
                    + "\",\"amount\":" + fields.getOrDefault("amount", "0")
                    + ",\"payee\":\"" + fields.getOrDefault("payee", "") + "\""
                    + ",\"expires_at\":" + fields.getOrDefault("expires_at", "0") + "}");
        });
    }

    /**
     * {@code POST /wallet/api/chains/open} — live-signed. Body
     * {@code {"tip":"<64 hex>","links":20,"per_link":1}}, optionally {@code "payee"} and
     * {@code "ttl_seconds"}. Reserves {@code links × per_link} coins against a chain whose seed
     * only the wallet has.
     *
     * <p>The wallet sends a hash, never the seed. One 32-byte secret at home therefore stands in
     * for a purse of notes: no signature per coin, no note per coin, and nothing the ledger holds
     * could spend it.
     */
    static void serveChainOpen(ChannelHandlerContext ctx, byte[] body, AicoinLedger ledger, String issuer) {
        String text = new String(body, CharsetUtil.UTF_8);
        String tip = field(text, "tip");
        if (tip == null || !tip.matches("[0-9a-f]{64}")) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "tip must be 64 lowercase hex characters");
            return;
        }
        int links = (int) number(text, "links", 0);
        if (links <= 0 || links > HashChain.MAX_LINKS) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "links must be between 1 and " + HashChain.MAX_LINKS);
            return;
        }
        double perLink = number(text, "per_link", 1);
        if (!(perLink > 0)) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "per_link must be positive");
            return;
        }
        String payee = field(text, "payee");
        if (payee != null && !payee.matches("[0-9a-fA-F]{64}")) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "payee must be a 64-character address");
            return;
        }
        long expiresAt = Instant.now().getEpochSecond() + ttlSeconds(text);
        ledger.openChain(issuer, tip, links, perLink,
                expiresAt, payee == null ? "" : payee.toLowerCase(java.util.Locale.ROOT), result -> {
                    if (!result.isReachable()) {
                        sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not reach the ledger");
                        return;
                    }
                    if (!result.isOk()) {
                        sendJson(ctx, "{\"opened\":false,\"reason\":\"" + result.getDetail() + "\"}");
                        return;
                    }
                    sendJson(ctx, "{\"opened\":true,\"chain\":\"" + tip + "\",\"links\":" + links
                            + ",\"per_link\":" + Note.formatAmount(perLink)
                            + ",\"reserved\":" + result.getDetail()
                            + ",\"expires_at\":" + expiresAt + "}");
                });
    }

    /**
     * {@code POST /wallet/api/chains/redeem} — live-signed. Body
     * {@code {"chain":"<opening tip>","preimage":"<64 hex>","steps":3}}.
     *
     * <p>Handing over a link is the payment: whoever holds one can prove it belongs to the chain by
     * hashing forward, and cannot work out the links behind it. The tip advances on redemption, so
     * the same link is spendable exactly once and the chain can never pay out more than it reserved.
     */
    static void serveChainRedeem(ChannelHandlerContext ctx, byte[] body, AicoinLedger ledger, String holder) {
        String text = new String(body, CharsetUtil.UTF_8);
        String chain = field(text, "chain");
        String preimage = field(text, "preimage");
        int steps = (int) number(text, "steps", 0);
        if (chain == null || !chain.matches("[0-9a-f]{64}") || preimage == null || !preimage.matches("[0-9a-f]{64}")) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "chain and preimage must be 64 lowercase hex characters");
            return;
        }
        if (steps <= 0 || steps > HashChain.MAX_LINKS) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "steps must be between 1 and " + HashChain.MAX_LINKS);
            return;
        }
        ledger.redeemChain(holder, chain, preimage, steps, result -> {
            if (!result.isReachable()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not reach the ledger");
                return;
            }
            if (!result.isOk()) {
                sendJson(ctx, "{\"credited\":false,\"reason\":\"" + result.getDetail() + "\"}");
                return;
            }
            sendJson(ctx, "{\"credited\":true,\"amount\":" + result.getDetail()
                    + ",\"balance\":" + result.getValue() + "}");
        });
    }

    /** {@code GET /wallet/api/chains/status/{opening tip}} — how much of a chain is left. */
    static void serveChainStatus(ChannelHandlerContext ctx, AicoinLedger ledger, String chain) {
        if (!chain.matches("[0-9a-fA-F]{64}")) {
            sendError(ctx, HttpResponseStatus.BAD_REQUEST, "chain must be 64 hex characters");
            return;
        }
        ledger.chainState(chain.toLowerCase(java.util.Locale.ROOT), state -> {
            if (!state.isPresent()) {
                sendError(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, "could not reach the ledger");
                return;
            }
            Map<String, String> fields = state.get();
            if (fields.isEmpty()) {
                sendJson(ctx, "{\"state\":\"unknown\"}");
                return;
            }
            sendJson(ctx, "{\"state\":\"open\",\"remaining\":" + fields.getOrDefault("remaining", "0")
                    + ",\"per_link\":" + fields.getOrDefault("per_link", "0")
                    + ",\"payee\":\"" + fields.getOrDefault("payee", "") + "\""
                    + ",\"expires_at\":" + fields.getOrDefault("expires_at", "0") + "}");
        });
    }

    private static double number(String body, String name, double fallback) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*(-?[0-9.]+)").matcher(body);
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<Double> amounts(String body) {
        List<Double> amounts = new ArrayList<>();
        Matcher list = Pattern.compile("\"amounts\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(body);
        if (list.find()) {
            for (String piece : list.group(1).split(",")) {
                String trimmed = piece.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    amounts.add(Double.parseDouble(trimmed));
                } catch (NumberFormatException e) {
                    return List.of();
                }
            }
            return amounts;
        }
        Matcher single = Pattern.compile("\"amount\"\\s*:\\s*(-?[0-9.]+)").matcher(body);
        if (single.find()) {
            try {
                amounts.add(Double.parseDouble(single.group(1)));
            } catch (NumberFormatException e) {
                return List.of();
            }
        }
        return amounts;
    }

    private static long ttlSeconds(String body) {
        Matcher matcher = Pattern.compile("\"ttl_seconds\"\\s*:\\s*([0-9]+)").matcher(body);
        if (!matcher.find()) {
            return DEFAULT_TTL_SECONDS;
        }
        try {
            return Math.min(MAX_TTL_SECONDS, Math.max(60, Long.parseLong(matcher.group(1))));
        } catch (NumberFormatException e) {
            return DEFAULT_TTL_SECONDS;
        }
    }

    private static String field(String body, String name) {
        Matcher matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void sendJson(ChannelHandlerContext ctx, String json) {
        send(ctx, HttpResponseStatus.OK, json);
    }

    private static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        send(ctx, status, "{\"error\":\"" + message + "\"}");
    }

    private static void send(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
        byte[] bytes = json.getBytes(CharsetUtil.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        HttpUtil.setContentLength(response, bytes.length);
        ctx.writeAndFlush(response);
    }
}
