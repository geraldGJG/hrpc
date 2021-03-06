> 在后续一段时间里， 我会写一系列文章来讲述如何实现一个RPC框架。 这是系列第二篇文章， 主要讲述了如何利用Spring以及Java的动态代理简化调用别的服务的代码。

在本系列第一篇文章中，我们说到了RPC框架需要关注的第一个点，通过创建代理的方式来简化客户端代码。

## 如果不使用代理？
如果我们不用代理去帮我们操心那些服务寻址、网络通信的问题，我们的代码会怎样？ 

我们每调用一次远端服务，就要在业务代码中重复一遍那些复杂的逻辑，这肯定是不能接受的！

## 目标代码
而我们的目标是写出简洁的代码，就像这样：

```
//这个接口应该被单独打成一个jar包，同时被server和client所依赖
@RPCService(HelloService.class)
public interface HelloService {

    String hello(String name);
}

@Component
@Slf4j
public class AnotherService {
    @Autowired
    HelloService helloService;

    public void callHelloService() {
		//就像调用本地方法一样自如！
        log.info("Result of callHelloService: {}", helloService.hello("world"));
    }
}

@EnableRPCClients(basePackages = {"pw.hshen.hrpc"})
public class HelloClient {

    public static void main(String[] args) throws Exception {
        ApplicationContext context = new ClassPathXmlApplicationContext("spring.xml");
        AnotherService anotherService = context.getBean(AnotherService.class);
        anotherService.callHelloService();
    }
}


```
代码中的AnotherService可以简单调用远端的HelloService的方法，就像调用本地的service一样简单！ 在这段代码中，HelloService可以视作server, 而AnotherService则是它的调用者，可以视作是client。

## 实现思路
### 1.获取要被创建代理的接口
首先，我们要知道需要为哪些接口来创建代理。

我们需要为这种特殊的接口创建一个注解来标注，即RPCService。然后我们就可以通过扫描某个包下面所有包含这个注解的interface来获取了。

那么，怎么知道要扫描哪个包呢？方法就是获取MainClass的EnableRPCClients注解的basePackages的值。

### 2.为这些接口创建动态代理
我们可以利用jdk的动态代理来做这件事儿：

```
// Interface是需要被创建代理的那个接口
Proxy.newProxyInstance(
            interface.getClassLoader(),
            new Class<?>[]{interface},
            new InvocationHandler() {
                @Override
                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			// TODO: Do RPC action here and return the result 
                }
            
```

### 3.将创建出来的代理对象注册到bean容器中
关于如何动态向spring容器中注册自定义的bean， 可以参考[这篇文章](http://www.logicbig.com/tutorials/spring-framework/spring-core/bean-definition/)。
在我的框架中， 我选择了使用BeanDefinitionRegistryPostProcessor所提供的hook。

注入到bean容器之后，我们就可以在代码中愉快的用Autowired等注解来获取所创建的代理啦！

## 完整代码
#### 定义需要的注解

```
/**
 * @author hongbin
 * Created on 22/10/2017
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EnableRPCClients {
	String[] basePackages() default {};
}
```

```
/**
 * @author hongbin
 * Created on 21/10/2017
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
@Inherited
public @interface RPCService {

	Class<?> value();
}
```



#### 利用Spring的hook机制， 向容器中注册我们自己的proxy bean：

```
/**
 * Register proxy bean for required client in bean container.
 * 1. Get interfaces with annotation RPCService
 * 2. Create proxy bean for the interfaces and register them
 *
 * @author hongbin
 * Created on 21/10/2017
 */
@Slf4j
@RequiredArgsConstructor
public class ServiceProxyProvider extends PropertySourcesPlaceholderConfigurer implements BeanDefinitionRegistryPostProcessor {

	@NonNull
	private ServiceDiscovery serviceDiscovery;

	@Override
	public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
		log.info("register beans");
		ClassPathScanningCandidateComponentProvider scanner = getScanner();
		scanner.addIncludeFilter(new AnnotationTypeFilter(RPCService.class));

		for (String basePackage: getBasePackages()) {
			Set<BeanDefinition> candidateComponents = scanner
					.findCandidateComponents(basePackage);
			for (BeanDefinition candidateComponent : candidateComponents) {
				if (candidateComponent instanceof AnnotatedBeanDefinition) {
					AnnotatedBeanDefinition beanDefinition = (AnnotatedBeanDefinition) candidateComponent;
					AnnotationMetadata annotationMetadata = beanDefinition.getMetadata();

					BeanDefinitionHolder holder = createBeanDefinition(annotationMetadata);
					BeanDefinitionReaderUtils.registerBeanDefinition(holder, registry);
				}
			}
		}
	}

	private ClassPathScanningCandidateComponentProvider getScanner() {
		return new ClassPathScanningCandidateComponentProvider(false) {

			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
				if (beanDefinition.getMetadata().isIndependent()) {

					if (beanDefinition.getMetadata().isInterface()
							&& beanDefinition.getMetadata().getInterfaceNames().length == 1
							&& Annotation.class.getName().equals(beanDefinition.getMetadata().getInterfaceNames()[0])) {

						try {
							Class<?> target = Class.forName(beanDefinition.getMetadata().getClassName());
							return !target.isAnnotation();
						} catch (Exception ex) {

							log.error("Could not load target class: {}, {}",
									beanDefinition.getMetadata().getClassName(), ex);
						}
					}
					return true;
				}
				return false;
			}
		};
	}

	private BeanDefinitionHolder createBeanDefinition(AnnotationMetadata annotationMetadata) {
		String className = annotationMetadata.getClassName();
		log.info("Creating bean definition for class: {}", className);

		BeanDefinitionBuilder definition = BeanDefinitionBuilder.genericBeanDefinition(ProxyFactoryBean.class);
		String beanName = StringUtils.uncapitalize(className.substring(className.lastIndexOf('.') + 1));

		definition.addPropertyValue("type", className);
		definition.addPropertyValue("serviceDiscovery", serviceDiscovery);

		return new BeanDefinitionHolder(definition.getBeanDefinition(), beanName);
	}

	private Set<String> getBasePackages() {
		String[] basePackages = getMainClass().getAnnotation(EnableRPCClients.class).basePackages();
		Set set = new HashSet<>();
		Collections.addAll(set, basePackages);
		return set;
	}

	private Class<?> getMainClass() {
		for (final Map.Entry<String, String> entry : System.getenv().entrySet()) {
			if (entry.getKey().startsWith("JAVA_MAIN_CLASS")) {
				String mainClass = entry.getValue();
				log.debug("Main class: {}", mainClass);
				try {
					return Class.forName(mainClass);
				} catch (ClassNotFoundException e) {
					throw new IllegalStateException("Cannot determine main class.");
				}
			}
		}
		throw new IllegalStateException("Cannot determine main class.");
	}

	@Override
	public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {

	}
}

```

#### 对应的ProxyBeanFactory：

```
/**
 * FactoryBean for service proxy
 *
 * @author hongbin
 * Created on 24/10/2017
 */
@Slf4j
@Data
public class ProxyFactoryBean implements FactoryBean<Object> {
	private Class<?> type;

	private ServiceDiscovery serviceDiscovery;

	@SuppressWarnings("unchecked")
	@Override
	public Object getObject() throws Exception {
		return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, this::doInvoke);
	}

	@Override
	public Class<?> getObjectType() {
		return this.type;
	}

	@Override
	public boolean isSingleton() {
		return true;
	}

	private Object doInvoke(Object proxy, Method method, Object[] args) throws Throwable {
		// TODO：这里处理服务发现、负载均衡、网络通信等逻辑
	}
}

```


就这样， 我们实现了客户端启动时的扫包、创建代理的过程，接下来要做的事情就只是填充代理的逻辑了。 完整代码请看[我的github](https://github.com/hshenCode/hrpc)。
