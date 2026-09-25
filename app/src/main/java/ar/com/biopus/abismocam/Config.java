package ar.com.biopus.abismocam;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class Config {
    String sourceName = "abismoCam";
    String address = "/camara/disparo";
    boolean broadcast = false;
    String broadcastIp = "";
    int broadcastPort = 9000;
    boolean frontCamera = true;
    int width = 960;
    int height = 540;
    int fps = 30;
    final List<Osc.Destination> destinations = new ArrayList<>();

    List<Osc.Destination> targets() {
        if (broadcast) {
            if (broadcastIp.isEmpty()) return Collections.emptyList();
            return Collections.singletonList(new Osc.Destination("Broadcast", broadcastIp, broadcastPort, true));
        }
        return new ArrayList<>(destinations);
    }

    static Config load(Context context) {
        Config config = new Config();
        String json = context.getSharedPreferences("config", Context.MODE_PRIVATE).getString("json", "{}");
        try {
            JSONObject obj = new JSONObject(json);
            config.sourceName = "abismoCam";
            config.address = obj.optString("address", config.address);
            Osc.validateAddress(config.address);
            config.broadcast = obj.optBoolean("broadcast", false);
            config.broadcastIp = obj.optString("broadcastIp", "");
            if (!config.broadcastIp.isEmpty()) Osc.ipv4(config.broadcastIp);
            config.broadcastPort = obj.optInt("broadcastPort", 9000);
            if (config.broadcastPort < 1 || config.broadcastPort > 65535) config.broadcastPort = 9000;
            // La primera actualización a la interfaz de selfie empieza con la frontal.
            config.frontCamera = obj.optInt("schemaVersion", 1) < 2 || obj.optBoolean("frontCamera", true);
            if (obj.optInt("schemaVersion", 1) >= 3) {
                int width = obj.optInt("width", 960);
                config.width = width == 640 || width == 1280 ? width : 960;
                config.height = config.width * 9 / 16;
                config.fps = obj.optInt("fps", 30) == 15 ? 15 : 30;
            }
            JSONArray list = obj.optJSONArray("destinations");
            if (list != null) for (int i = 0; i < list.length(); i++) {
                JSONObject d = list.getJSONObject(i);
                config.destinations.add(new Osc.Destination(d.getString("name"), d.getString("ip"), d.getInt("port"), d.getBoolean("enabled")));
            }
        } catch (JSONException | IllegalArgumentException invalid) { return new Config(); }
        return config;
    }

    void save(Context context) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("schemaVersion", 3).put("sourceName", sourceName).put("address", address).put("broadcast", broadcast)
                .put("broadcastIp", broadcastIp).put("broadcastPort", broadcastPort)
                .put("frontCamera", frontCamera).put("width", width).put("fps", fps);
            JSONArray list = new JSONArray();
            for (Osc.Destination d : destinations)
                list.put(new JSONObject().put("name", d.name).put("ip", d.ip).put("port", d.port).put("enabled", d.enabled));
            obj.put("destinations", list);
            context.getSharedPreferences("config", Context.MODE_PRIVATE).edit().putString("json", obj.toString()).apply();
        } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }
}
