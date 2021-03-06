package io.smallrye.reactive.messaging;

import io.reactivex.Flowable;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Outgoing;
import org.eclipse.microprofile.reactive.streams.operators.ReactiveStreams;
import org.reactivestreams.Publisher;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SourceOnly {

  @Outgoing("count")
  public Publisher<Message<String>> source() {
    return Flowable.range(1, 10)
      .map(i -> Integer.toString(i))
      .map(Message::of)
      .flatMap(m -> ReactiveStreams.of(m, m).buildRs());
  }



}
