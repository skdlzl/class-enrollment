import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/** Small real HTTP JVM used only by Windows CI to verify PID tracking and JAR release. */
public class WindowsDiagnosticProbe {
    public static void main(String[] args) throws Exception {
        int port=Integer.parseInt(args[0]);
        String folder=args[1].replace("\\","\\\\").replace("\"","\\\"");
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.createContext("/api/health",exchange -> {
            byte[] body="{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });
        server.createContext("/internal/analysis/identity",exchange -> {
            byte[] body=("{\"pid\":"+ProcessHandle.current().pid()+",\"folder\":\""+folder+"\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();new CountDownLatch(1).await();
    }
}
