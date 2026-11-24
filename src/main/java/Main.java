package com.crisisflow;

public class Main {

    public static void main(String[] args) {
        try {
            int port = 8080;
            String p = System.getenv("CRISISFLOW_PORT");
            if (p != null && !p.isBlank()) {
                port = Integer.parseInt(p.trim());
            }

            Database.getConnection().close();
            Database.ensureSchema();

            ApiServer server = new ApiServer(port);
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::stop));

            // Serve anything that was left waiting while the app was down.
            Allocation.runQuietly();

            System.out.println("CrisisFlow is running.");
            System.out.println("Dashboard: http://localhost:" + port + "/");
            System.out.println("Map:       http://localhost:" + port + "/map.html");
            if (System.getenv("CRISISFLOW_API_KEY") == null) {
                System.out.println("Note: CRISISFLOW_API_KEY is not set, "
                    + "so write endpoints are open to anyone who can reach this port.");
            }

        } catch (Exception e) {
            System.err.println("Could not start CrisisFlow.");
            e.printStackTrace();
            System.exit(1);
        }
    }
}
