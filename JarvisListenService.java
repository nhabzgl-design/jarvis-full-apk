
private String askServer(String b64) throws Exception {
    JSONArray messages = new JSONArray();

    JSONObject um = new JSONObject();
    um.put("role", "user");

    JSONArray content = new JSONArray();

    JSONObject audio = new JSONObject();
    audio.put("type", "audio");
    audio.put("mime", "audio/wav");
    audio.put("data", b64);
    content.put(audio);

    JSONObject txt = new JSONObject();
    txt.put("type", "text");
    txt.put("text", "(Voice command recorded right after the wake word. Understand it and act.)");
    content.put(txt);

    um.put("content", content);
    messages.put(um);

    // Tool definitions sent to the server
    JSONArray toolSchemas = new JSONArray(
        prefs().getString("tools", "[]")
    );

    String system = prefs().getString("system", "");
    String finalText = "";

    for (int round = 0; round < 4; round++) {
        JSONObject payload = new JSONObject();
        payload.put("system", system);
        payload.put("tools", toolSchemas);
        payload.put("messages", messages);

        JSONObject resp = postChat(payload);
        JSONArray rc = resp.getJSONArray("content");

        JSONObject am = new JSONObject();
        am.put("role", "assistant");
        am.put("content", rc);
        messages.put(am);

        JSONArray results = new JSONArray();

        for (int i = 0; i < rc.length(); i++) {
            JSONObject block = rc.getJSONObject(i);
            String type = block.optString("type", "");

            if ("text".equals(type)) {
                finalText = block.optString("text", finalText);

            } else if ("tool_use".equals(type)) {
                JSONObject in = block.optJSONObject("input");

                if (in == null) {
                    in = new JSONObject();
                }

                // Execute the requested Jarvis tool
                String res = tools.run(
                    block.optString("name", ""),
                    in
                );

                JSONObject tr = new JSONObject();
                tr.put("type", "tool_result");
                tr.put("tool_use_id", block.optString("id", ""));
                tr.put("content", res);
                results.put(tr);
            }
        }

        if (results.length() == 0) {
            break;
        }

        JSONObject rm = new JSONObject();
        rm.put("role", "user");
        rm.put("content", results);
        messages.put(rm);
    }

    return finalText.replaceAll("[*#_`]", "");
}
