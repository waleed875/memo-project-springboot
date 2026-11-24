import java.net.HttpURLConnection;
import java.net.URI;

/** Standalone JRE-compatible Docker readiness probe; no external curl needed. */
public final class HealthProbe {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("SERVER_PORT", "8080"));
        HttpURLConnection connection = (HttpURLConnection) URI.create(
            "http://127.0.0.1:" + port + "/actuator/health/readiness"
        ).toURL().openConnection();
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(2000);
        connection.setRequestMethod("GET");
        try {
            if (connection.getResponseCode() != 200) {
                System.exit(1);
            }
        } finally {
            connection.disconnect();
        }
    }
}
