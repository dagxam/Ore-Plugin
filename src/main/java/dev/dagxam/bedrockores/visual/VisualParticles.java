public void save() {
    YamlConfiguration yml = new YamlConfiguration();

    int i = 0;
    for (Map.Entry<String, NodeData> e : nodes.entrySet()) {
        String[] parts = e.getKey().split(":");
        UUID world = UUID.fromString(parts[0]);
        int x = Integer.parseInt(parts[1]);
        int y = Integer.parseInt(parts[2]);
        int z = Integer.parseInt(parts[3]);

        NodeData nd = e.getValue();
        String path = "nodes.n" + (i++);
        yml.set(path + ".world", world.toString());
        yml.set(path + ".x", x);
        yml.set(path + ".y", y);
        yml.set(path + ".z", z);
        yml.set(path + ".type", nd.oreMaterial.name());
        yml.set(path + ".hits", nd.hitsRemaining);
        yml.set(path + ".maxHits", nd.maxHits);
    }

    for (Map.Entry<UUID, Set<Long>> e : processedChunks.entrySet()) {
        List<String> list = new ArrayList<>();
        for (Long ck : e.getValue()) {
            int cx = (int) (ck >> 32);
            int cz = (int) (ck & 0xffffffffL);
            list.add(cx + ":" + cz);
        }
        yml.set("processedChunks." + e.getKey().toString(), list);
    }

    int r = 0;
    for (Map.Entry<String, RespawnData> e : respawns.entrySet()) {
        String[] parts = e.getKey().split(":");
        UUID world = UUID.fromString(parts[0]);
        int x = Integer.parseInt(parts[1]);
        int y = Integer.parseInt(parts[2]);
        int z = Integer.parseInt(parts[3]);

        RespawnData rd = e.getValue();
        String path = "respawns.r" + (r++);
        yml.set(path + ".world", world.toString());
        yml.set(path + ".x", x);
        yml.set(path + ".y", y);
        yml.set(path + ".z", z);
        yml.set(path + ".type", rd.oreMaterial.name());
        yml.set(path + ".dueAt", rd.dueAtMillis);
    }

    try {
        yml.save(dataFile);
    } catch (IOException ex) {
        plugin.getLogger().severe("Failed to save nodes.yml: " + ex.getMessage());
    }
}
