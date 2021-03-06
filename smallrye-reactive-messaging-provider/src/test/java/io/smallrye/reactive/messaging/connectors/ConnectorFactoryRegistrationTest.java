package io.smallrye.reactive.messaging.connectors;

import io.smallrye.reactive.messaging.WeldTestBase;
import org.eclipse.microprofile.reactive.messaging.spi.ConnectorLiteral;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ConnectorFactoryRegistrationTest extends WeldTestBase {

  @Test
  public void test() {
    initializer.addBeanClasses(DummyBean.class, io.smallrye.config.inject.ConfigProducer.class);

    initialize();

    assertThat(registry(container).getPublishers("dummy-source")).isNotEmpty();
    assertThat(registry(container).getSubscribers("dummy-sink")).isNotEmpty();

    MyDummyConnector bean = container.select(MyDummyConnector.class, ConnectorLiteral.of("dummy")).get();
    assertThat(bean.list()).containsExactly("8", "10", "12");
    assertThat(bean.gotCompletion()).isTrue();
    assertThat(bean.getConfigs()).hasSize(4).allSatisfy(config -> {
      assertThat(config.getValue("foo", String.class)).isEqualTo("bar");
      assertThat(config.getOptionalValue("foo", String.class)).contains("bar");
    });
  }

  @Test
  public void testLegacy() {
    initializer.addBeanClasses(DummyBean.class, io.smallrye.config.inject.ConfigProducer.class);

    initialize();

    assertThat(registry(container).getPublishers("legacy-dummy-source")).isNotEmpty();
    assertThat(registry(container).getSubscribers("legacy-dummy-sink")).isNotEmpty();

    MyDummyConnector bean = container.select(MyDummyConnector.class, ConnectorLiteral.of("dummy")).get();
    assertThat(bean.list()).containsExactly("8", "10", "12");
    assertThat(bean.gotCompletion()).isTrue();
  }

}
