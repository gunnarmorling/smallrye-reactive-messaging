package io.smallrye.reactive.messaging.amqp;

import io.reactivex.Flowable;
import io.reactivex.functions.Function;
import io.reactivex.processors.MulticastProcessor;
import io.vertx.amqp.AmqpClientOptions;
import io.vertx.amqp.AmqpReceiverOptions;
import io.vertx.axle.amqp.AmqpClient;
import io.vertx.axle.amqp.AmqpMessageBuilder;
import io.vertx.axle.amqp.AmqpReceiver;
import io.vertx.axle.amqp.AmqpSender;
import io.vertx.axle.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.reactivex.core.Vertx;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.spi.Connector;
import org.eclipse.microprofile.reactive.messaging.spi.IncomingConnectorFactory;
import org.eclipse.microprofile.reactive.messaging.spi.OutgoingConnectorFactory;
import org.eclipse.microprofile.reactive.streams.operators.PublisherBuilder;
import org.eclipse.microprofile.reactive.streams.operators.ReactiveStreams;
import org.eclipse.microprofile.reactive.streams.operators.SubscriberBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.BeforeDestroyed;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

@ApplicationScoped
@Connector(AmqpConnector.CONNECTOR_NAME)
public class AmqpConnector implements IncomingConnectorFactory, OutgoingConnectorFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(AmqpConnector.class);
  static final String CONNECTOR_NAME = "smallrye-amqp";

  private AmqpClient client;

  @Inject
  private Instance<Vertx> instanceOfVertx;

  private boolean internalVertxInstance = false;
  private Vertx vertx;

  public void terminate(@Observes @BeforeDestroyed(ApplicationScoped.class) Object event) {
    if (internalVertxInstance) {
      vertx.close();
    }
  }

  @PostConstruct
  void init() {
    if (instanceOfVertx == null || instanceOfVertx.isUnsatisfied()) {
      internalVertxInstance = true;
      this.vertx = Vertx.vertx();
    } else {
      this.vertx = instanceOfVertx.get();
    }
  }

  AmqpConnector() {
    this.vertx = null;
  }

  private synchronized AmqpClient getClient(Config config) {
    // TODO Should we support having a single client (1 host) or multiple clients.
    if (client != null) {
      return client;
    }
    String username = config.getOptionalValue("username", String.class).orElse(null);
    String password = config.getOptionalValue("password", String.class).orElse(null);
    String host = config.getOptionalValue("host", String.class)
      .orElseThrow(() -> new IllegalArgumentException("Host must be set"));
    int port = config.getOptionalValue("port", Integer.class).orElse(5672);
    String containerId = config.getOptionalValue("containerId", String.class).orElse(null);

    AmqpClientOptions options = new AmqpClientOptions()
      .setUsername(username)
      .setPassword(password)
      .setHost(host)
      .setPort(port)
      .setContainerId(containerId)
      // TODO Make these values configurable:
      .setReconnectAttempts(100)
      .setReconnectInterval(10)
      .setConnectTimeout(1000);
    client = AmqpClient.create(new io.vertx.axle.core.Vertx(vertx.getDelegate()), options);
    return client;
  }

  private Flowable<? extends Message> getStreamOfMessages(AmqpReceiver receiver) {
    return Flowable.defer(
      () -> Flowable.fromPublisher(receiver.toPublisher())
    )
      .map((Function<io.vertx.axle.amqp.AmqpMessage, AmqpMessage>) AmqpMessage::new);
  }

  private String getAddressOrFail(Config config) {
    return
      config.getOptionalValue("address", String.class)
        .orElseGet(
          () -> config.getOptionalValue("channel-name", String.class)
            .orElseThrow(() -> new IllegalArgumentException("Address must be set"))
        );
  }

  @Override
  public PublisherBuilder<? extends Message> getPublisherBuilder(Config config) {
    String address = getAddressOrFail(config);
    boolean broadcast = config.getOptionalValue("broadcast", Boolean.class).orElse(false);
    boolean durable = config.getOptionalValue("durable", Boolean.class).orElse(true);
    boolean autoAck = config.getOptionalValue("auto-acknowledgement", Boolean.class).orElse(false);
    CompletionStage<AmqpReceiver> future = getClient(config)
      .connect()
      .thenCompose(connection -> connection.createReceiver(address, new AmqpReceiverOptions()
        .setAutoAcknowledgement(autoAck)
        .setDurable(durable)));

    PublisherBuilder<? extends Message> builder = ReactiveStreams
      .fromCompletionStage(future)
      .flatMapRsPublisher(this::getStreamOfMessages);

    if (broadcast) {
      return builder.via(MulticastProcessor.create());
    }
    return builder;
  }


  @Override
  public SubscriberBuilder<? extends Message, Void> getSubscriberBuilder(Config config) {
    String address = getAddressOrFail(config);
    boolean durable = config.getOptionalValue("durable", Boolean.class).orElse(true);
    long ttl = config.getOptionalValue("ttl", Long.class).orElse(0L);

    AtomicReference<AmqpSender> sender = new AtomicReference<>();
    return ReactiveStreams.<Message>builder().flatMapCompletionStage(message -> {
      AmqpSender as = sender.get();
      if (as == null) {
        return getClient(config)
          .createSender(address)
          .thenApply(s -> {
            sender.set(s);
            return s;
          })
          .thenCompose(s -> send(s, message, durable, ttl));
      } else {
        return send(as, message, durable, ttl);
      }
    }).ignore();
  }

  private CompletionStage<Message> send(AmqpSender sender, Message msg, boolean durable, long ttl) {
    LOGGER.info("Sending... {}", msg.getPayload());
    io.vertx.axle.amqp.AmqpMessage amqp;

    if (msg instanceof AmqpMessage) {
      amqp = ((AmqpMessage) msg).getAmqpMessage();
    } else if (msg.getPayload() instanceof io.vertx.axle.amqp.AmqpMessage) {
      amqp = (io.vertx.axle.amqp.AmqpMessage) msg.getPayload();
    } else if (msg.getPayload() instanceof io.vertx.amqp.AmqpMessage) {
      amqp = new io.vertx.axle.amqp.AmqpMessage((io.vertx.amqp.AmqpMessage) msg.getPayload());
    } else {
      amqp = convertToAmqpMessage(msg.getPayload(), durable, ttl);
    }
    return sender.sendWithAck(amqp).thenCompose(x -> msg.ack()).thenApply(x -> msg);
  }

  private io.vertx.axle.amqp.AmqpMessage convertToAmqpMessage(Object payload, boolean durable, long ttl) {
    AmqpMessageBuilder builder = io.vertx.axle.amqp.AmqpMessage.create();

    if (durable) {
      builder.durable(true);
    }
    if (ttl > 0) {
      builder.ttl(ttl);
    }

    if (payload instanceof String) {
      builder.withBody((String) payload);
    } else if (payload instanceof Boolean) {
      builder.withBooleanAsBody((Boolean) payload);
    } else if (payload instanceof Buffer) {
      builder.withBufferAsBody((Buffer) payload);
    } else if (payload instanceof Byte) {
      builder.withByteAsBody((Byte) payload);
    } else if (payload instanceof Character) {
      builder.withCharAsBody((Character) payload);
    } else if (payload instanceof Double) {
      builder.withDoubleAsBody((Double) payload);
    } else if (payload instanceof Float) {
      builder.withFloatAsBody((Float) payload);
    } else if (payload instanceof Instant) {
      builder.withInstantAsBody((Instant) payload);
    } else if (payload instanceof Integer) {
      builder.withIntegerAsBody((Integer) payload);
    } else if (payload instanceof JsonArray) {
      builder.withJsonArrayAsBody((JsonArray) payload);
    } else if (payload instanceof JsonObject) {
      builder.withJsonObjectAsBody((JsonObject) payload);
    } else if (payload instanceof Long) {
      builder.withLongAsBody((Long) payload);
    } else if (payload instanceof Short) {
      builder.withShortAsBody((Short) payload);
    } else if (payload instanceof UUID) {
      builder.withUuidAsBody((UUID) payload);
    } else {
      builder.withBody(payload.toString());
    }

    return builder.build();
  }

  @PreDestroy
  public synchronized void close() {
    if (client != null) {
      client.close();
    }
  }
}
