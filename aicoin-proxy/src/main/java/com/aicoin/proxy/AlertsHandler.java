package com.aicoin.proxy;

import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.CharsetUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The admin app's side of {@link AdminAlerts}, per CONTRACT.md's "Admin alerts":
 * {@code GET /admin/alerts}, {@code POST /admin/alerts/test}, {@code GET/POST /admin/devices} and
 * {@code DELETE /admin/devices/{token}}. All {@code X-Admin-Token} gated.
 */
final class AlertsHandler {

    private AlertsHandler() {
    }

    /** The kinds, whether pushes can go out at all, and the recent history. */
    static void serveAlerts(ChannelHandlerContext ctx, FullHttpRequest request, ProxyConfig config) {
        if (!AppCapsHandler.isAuthorized(request, config, ctx)) {
            return;
        }
        AdminAlerts alerts = AdminAlerts.get();
        StringBuilder sb = new StringBuilder("{\"apns_configured\":").append(alerts.isApnsConfigured())
                .append(",\"apns_key_id\":").append(Json.string(alerts.apnsKeyId()))
                .append(",\"wallet_low_coins\":").append(alerts.getWalletLowCoins())
                .append(",\"kinds\":[");
        boolean first = true;
        for (AdminAlerts.Kind k : AdminAlerts.Kind.values()) {
            if (k == AdminAlerts.Kind.TEST) {
                continue;
            }
            sb.append(first ? "" : ",").append("{\"id\":").append(Json.string(k.id))
                    .append(",\"label\":").append(Json.string(k.label))
                    .append(",\"debounce_minutes\":").append(k.debounceMinutes).append("}");
            first = false;
        }
        sb.append("],\"recent\":[");
        if (alerts.redis() == null) {
            AppCapsHandler.send(ctx, HttpResponseStatus.OK, sb.append("]}").toString());
            return;
        }
        alerts.redis().lrange(AdminAlerts.HISTORY_KEY, 0, 49).whenComplete((entries, err) -> {
            if (err == null) {
                sb.append(String.join(",", entries));
            }
            AppCapsHandler.send(ctx, HttpResponseStatus.OK, sb.append("]}").toString());
        });
    }

    /** Sends a test push to every registered device, now, and says what each one came to. */
    static void serveTest(ChannelHandlerContext ctx, FullHttpRequest request, ProxyConfig config) {
        if (!AppCapsHandler.isAuthorized(request, config, ctx)) {
            return;
        }
        AdminAlerts alerts = AdminAlerts.get();
        if (alerts.redis() == null) {
            AppCapsHandler.send(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, AppCapsHandler.error("alerts not running"));
            return;
        }
        alerts.send(AdminAlerts.Kind.TEST, "AICoin Admin", "Test notification from aicoin-proxy", results -> {
            StringBuilder sb = new StringBuilder("{\"apns_configured\":").append(alerts.isApnsConfigured())
                    .append(",\"results\":[");
            for (int i = 0; i < results.size(); i++) {
                sb.append(i == 0 ? "" : ",").append(Json.string(results.get(i)));
            }
            AppCapsHandler.send(ctx, HttpResponseStatus.OK, sb.append("]}").toString());
        });
    }

    static void serveDevices(ChannelHandlerContext ctx, FullHttpRequest request, ProxyConfig config) {
        if (!AppCapsHandler.isAuthorized(request, config, ctx)) {
            return;
        }
        AdminAlerts alerts = AdminAlerts.get();
        if (alerts.redis() == null) {
            AppCapsHandler.send(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, AppCapsHandler.error("alerts not running"));
            return;
        }
        alerts.redis().hgetall(AdminAlerts.DEVICES_KEY).whenComplete((devices, err) -> {
            if (err != null) {
                AppCapsHandler.send(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, AppCapsHandler.error("could not list devices"));
                return;
            }
            StringBuilder sb = new StringBuilder("{\"devices\":[");
            boolean first = true;
            for (Map.Entry<String, String> d : devices.entrySet()) {
                AdminAlerts.Device device = AdminAlerts.Device.fromJson(d.getKey(), d.getValue());
                sb.append(first ? "" : ",").append("{\"token\":").append(Json.string(device.token()))
                        .append(",\"environment\":").append(Json.string(device.development() ? "development" : "production"))
                        .append(",\"disabled\":").append(Json.write(new ArrayList<Object>(device.disabled()))).append("}");
                first = false;
            }
            AppCapsHandler.send(ctx, HttpResponseStatus.OK, sb.append("]}").toString());
        });
    }

    /** Parsed {@code POST /admin/devices} body, or the reason it is refused. */
    record Registration(AdminAlerts.Device device, String error) {
    }

    /** Body {@code {"token":"<hex>","environment":"development"|"production","disabled":["kind",...]}}. */
    static Registration parseRegistration(String body) {
        Object parsed;
        try {
            parsed = Json.load(body);
        } catch (RuntimeException e) {
            return new Registration(null, "body must be JSON");
        }
        if (!(parsed instanceof Map<?, ?> m)) {
            return new Registration(null, "body must be a JSON object");
        }
        if (!(m.get("token") instanceof String token) || !AdminAlerts.isValidDeviceToken(token)) {
            return new Registration(null, "token must be the APNs device token in hex");
        }
        Object env = m.get("environment");
        if (env != null && !"development".equals(env) && !"production".equals(env)) {
            return new Registration(null, "environment must be development or production");
        }
        List<String> disabled = new ArrayList<>();
        if (m.get("disabled") instanceof List<?> l) {
            for (Object o : l) {
                if (!(o instanceof String s) || AdminAlerts.Kind.byId(s).isEmpty()) {
                    return new Registration(null, "unknown alert kind in disabled: " + o);
                }
                disabled.add(s);
            }
        }
        return new Registration(new AdminAlerts.Device(token.toLowerCase(), "development".equals(env), disabled), null);
    }

    static void serveRegister(ChannelHandlerContext ctx, FullHttpRequest request, ProxyConfig config) {
        if (!AppCapsHandler.isAuthorized(request, config, ctx)) {
            return;
        }
        Registration r = parseRegistration(new String(ByteBufUtil.getBytes(request.content()), CharsetUtil.UTF_8));
        if (r.error() != null) {
            AppCapsHandler.send(ctx, HttpResponseStatus.BAD_REQUEST, AppCapsHandler.error(r.error()));
            return;
        }
        AdminAlerts alerts = AdminAlerts.get();
        if (alerts.redis() == null) {
            AppCapsHandler.send(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, AppCapsHandler.error("alerts not running"));
            return;
        }
        AdminAlerts.Device d = r.device();
        alerts.redis().hset(AdminAlerts.DEVICES_KEY, d.token(), d.toJson()).whenComplete((v, err) -> {
            if (err != null) {
                AppCapsHandler.send(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, AppCapsHandler.error("could not register device"));
                return;
            }
            AppCapsHandler.send(ctx, HttpResponseStatus.OK, "{\"registered\":true,\"environment\":"
                    + Json.string(d.development() ? "development" : "production")
                    + ",\"disabled\":" + Json.write(new ArrayList<Object>(d.disabled()))
                    + ",\"apns_configured\":" + alerts.isApnsConfigured() + "}");
        });
    }

    static void serveUnregister(ChannelHandlerContext ctx, FullHttpRequest request, ProxyConfig config, String token) {
        if (!AppCapsHandler.isAuthorized(request, config, ctx)) {
            return;
        }
        AdminAlerts alerts = AdminAlerts.get();
        if (alerts.redis() == null) {
            AppCapsHandler.send(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, AppCapsHandler.error("alerts not running"));
            return;
        }
        alerts.redis().hdel(AdminAlerts.DEVICES_KEY, token.toLowerCase()).whenComplete((n, err) -> {
            if (err != null) {
                AppCapsHandler.send(ctx, HttpResponseStatus.SERVICE_UNAVAILABLE, AppCapsHandler.error("could not remove device"));
                return;
            }
            AppCapsHandler.send(ctx, HttpResponseStatus.OK, "{\"removed\":" + (n != null && n > 0) + "}");
        });
    }
}
