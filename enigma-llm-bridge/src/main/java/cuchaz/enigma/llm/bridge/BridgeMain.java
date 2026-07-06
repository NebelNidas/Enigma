package cuchaz.enigma.llm.bridge;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public final class BridgeMain {
	private BridgeMain() {
	}

	public static void main(String[] args) throws IOException, InterruptedException {
		BridgeConfig config = BridgeConfig.load(args, System::getenv);
		BridgeProvider provider = BridgeProviders.create(config);
		BridgeServer server = new BridgeServer(config.host(), config.port(), config.authToken(), provider);
		server.start();
		System.out.printf("Enigma LLM bridge listening at http://%s:%d/v1 (%s)%n",
				config.host(), server.port(), provider.name());

		CountDownLatch stop = new CountDownLatch(1);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			try {
				server.close();
			} catch (Exception e) {
				System.err.println("Could not stop Enigma LLM bridge cleanly: " + e.getMessage());
			} finally {
				stop.countDown();
			}
		}, "enigma-llm-bridge-shutdown"));
		stop.await();
	}
}
