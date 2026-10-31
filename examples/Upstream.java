import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/** Small standalone API to try the proxy without another project. */
class Upstream {
    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 3000 : Integer.parseInt(args[0]);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = ("{\"status\":\"healthy\",\"method\":\"" + exchange.getRequestMethod() + "\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        server.start();
        System.out.println("Demo API listening on http://127.0.0.1:" + port);
    }
}
