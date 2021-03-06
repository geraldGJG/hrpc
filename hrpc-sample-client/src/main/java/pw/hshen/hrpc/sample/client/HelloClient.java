package pw.hshen.hrpc.sample.client;

import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;
import pw.hshen.hrpc.client.annotation.EnableRPCClients;

@EnableRPCClients(basePackages = {"pw.hshen.hrpc.sample.spi"})
public class HelloClient {

	public static void main(String[] args) throws Exception {
		new Thread(new Runnable() {
			@Override
			public void run() {
				ApplicationContext context = new ClassPathXmlApplicationContext("spring.xml");
				AnotherService anotherService = context.getBean(AnotherService.class);
				anotherService.callHelloService();
			}
		}).start();
	}
}
