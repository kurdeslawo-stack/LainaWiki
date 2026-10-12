package pl.laina.reforge.wiki;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;

/** Bound the downloaded XML, not just the description rendered after parsing it. */
final class WikiHttp {
    static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    static HttpResponse.BodyHandler<byte[]> limitedBody() {
        return ignored -> new LimitedBody(MAX_RESPONSE_BYTES);
    }

    static Document parseXml(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler() {
            @Override public void error(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
            @Override public void fatalError(org.xml.sax.SAXParseException error) throws org.xml.sax.SAXException { throw error; }
        });
        return builder.parse(new ByteArrayInputStream(xml));
    }

    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final long limit;
        private long received;
        private Flow.Subscription subscription;
        private boolean done;

        LimitedBody(long limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (done) return;
            for (ByteBuffer buffer : buffers) {
                received += buffer.remaining();
                if (received > limit) {
                    subscription.cancel();
                    onError(new IOException("Odpowiedź Wiki przekracza limit " + limit + " bajtów"));
                    return;
                }
            }
            delegate.onNext(buffers);
        }
        @Override public void onError(Throwable failure) {
            if (!done) { done = true; delegate.onError(failure); }
        }
        @Override public void onComplete() {
            if (!done) { done = true; delegate.onComplete(); }
        }
    }
}
