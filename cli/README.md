# aicoin — the command-line wallet and AI client

Everything the browser wallet page does, plus the two things a terminal is better at: making AI
calls through the proxy, and running a **consortium** call, where every configured model answers
one request and then reviews the answer until nobody objects.

```
cd cli && go build -o ~/.local/bin/aicoin .
```

Go 1.24+, no dependencies outside the standard library.

## First run

```
aicoin new        # creates ~/.aicoin/wallet.json
aicoin claim      # the free-coin faucet, once per wallet per hour
aicoin show       # address and balance
```

The wallet file **is** the wallet: an Ed25519 seed, written `0600`, with no server-side copy and no
recovery phrase. Back it up (`aicoin export`) or lose the coins.

## Commands

| | |
|---|---|
| `aicoin new [-force]` | create a wallet; refuses to overwrite one without `-force` |
| `aicoin show` | address and balance |
| `aicoin import -key <hex>` | adopt an existing key (a seed, or an expanded private key) |
| `aicoin export [-y]` | print the private key |
| `aicoin balance [address]` | any wallet's balance; yours if you name none |
| `aicoin claim` | take the faucet's grant |
| `aicoin send <address> <amount>` | transfer coins |
| `aicoin token [-days N]` | issue an API token for use elsewhere |
| `aicoin revoke` | invalidate every token issued so far |
| `aicoin ask [-ai p] [-model m] <prompt>` | one model, one answer |
| `aicoin consortium [flags] <prompt>` | every model, then reviewed until nobody objects |
| `aicoin call -ai <p> <path> [-data <json>]` | raw pass-through to a provider's own API |
| `aicoin price` / `aicoin health` | what a coin costs; which providers are live |

Common flags: `-url` (or `$AICOIN_PROXY_URL`, default `https://proxy.aicoin.oeaio.com`) and
`-wallet` (or `$AICOIN_WALLET`, default `~/.aicoin/wallet.json`). Flags may appear anywhere,
including after the prompt.

## Asking

```
$ aicoin ask "explain the balance gate in one sentence"
$ aicoin ask -ai kimi -model kimi-k2.7-code "review this function" < handler.go
```

`ask` sends the provider's own request to the provider's own path — the proxy forwards it untouched
— so `-ai` and `-model` have to agree. With no `-model` it uses each provider's current default.

## The consortium

```
$ aicoin consortium "what breaks first when a proxy meters billing per call?"
$ aicoin consortium -providers anthropic,kimi -rounds 2 -v -context @design.md "review this design"
```

Every panelist drafts an answer, an editor merges them, and then the whole panel reviews the result
round after round until one comes back with no comments — or until the round cap, whichever is
first. Every panelist sees the same shared record on every turn: the request, your `-context`, the
drafts, and every earlier round of comments.

The answer goes to **stdout** and everything else to stderr, so `aicoin consortium "..." > answer.md`
gives you the answer and nothing else. `-v` prints each round's comments; `-json` prints the proxy's
whole response.

It takes minutes, and **every turn is a paid call** — a four-model panel over two rounds is 13 calls
and is billed as 13. The last line says what it cost:

```
settled — a whole round with no comments | 2 round(s), 13 calls, 15 aicoin | panel anthropic,openai,google,kimi, editor anthropic
```

## Tokens

`aicoin token` issues one for other tools; `ask`, `call` and `consortium` don't need it — they mint
a one-hour token per call from the wallet key, which expires on its own.

A token can spend the wallet's coins on AI calls but cannot transfer them, so a leaked token cannot
drain a wallet. `aicoin revoke` invalidates every token issued so far, including the ad-hoc ones.
