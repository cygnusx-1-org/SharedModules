package com.liskovsoft.sharedutils.okhttp;

import org.junit.Test;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A query is POSTed as a DNS message (RFC 8484), and the answer's records come back with their TTL (PublicDnsResolver caches by it)
 */
public class OkHttpDohResolverTest {
    private static final HttpUrl URL = HttpUrl.get("https://dns.example/dns-query");

    @Test
    public void aQueryIsPostedAsADnsMessageAndItsAnswerRead() throws Exception {
        AtomicReference<Request> sent = new AtomicReference<>();
        AtomicReference<byte[]> sentBody = new AtomicReference<>();
        OkHttpDohResolver resolver = new OkHttpDohResolver(URL, server(200, request -> {
            sent.set(request);
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            sentBody.set(buffer.readByteArray());
            Message query = new Message(sentBody.get());
            return answer(query, "203.0.113.7", 120).toWire();
        }));
        Message query = Message.newQuery(Record.newRecord(Name.fromString("www.example."), Type.A, DClass.IN));

        Message answer = resolver.send(query);

        assertEquals("POST", sent.get().method());
        assertEquals(URL, sent.get().url());
        assertEquals("application/dns-message", sent.get().header("Accept"));
        assertEquals(MediaType.get("application/dns-message"), sent.get().body().contentType());
        assertArrayEquals(query.toWire(), sentBody.get());

        Record[] records = answer.getSectionArray(Section.ANSWER);
        assertEquals(1, records.length);
        assertEquals(InetAddress.getByName("203.0.113.7"), ((ARecord) records[0]).getAddress());
        assertEquals(120, records[0].getTTL());
    }

    /**
     * A server error isn't an answer: the lookup's result is TRY_AGAIN, and the next server is asked (see PublicDnsResolver)
     */
    @Test
    public void anHttpErrorIsAFailedQuery() throws Exception {
        OkHttpDohResolver resolver = new OkHttpDohResolver(URL, server(503, request -> new byte[0]));
        Message query = Message.newQuery(Record.newRecord(Name.fromString("www.example."), Type.A, DClass.IN));

        try {
            resolver.send(query);
            fail("An HTTP 503 was taken for an answer");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("503"));
        }
    }

    interface Server {
        byte[] answer(Request request) throws Exception;
    }

    /**
     * Answers in place of the network
     */
    private static OkHttpClient server(int code, Server server) {
        return new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    byte[] body;

                    try {
                        body = server.answer(chain.request());
                    } catch (Exception e) {
                        throw new IOException(e);
                    }

                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_2)
                            .code(code)
                            .message("")
                            .body(ResponseBody.create(body, MediaType.get("application/dns-message")))
                            .build();
                })
                .build();
    }

    static Message answer(Message query, String address, long ttl) throws Exception {
        Message answer = new Message(query.getHeader().getID());
        answer.getHeader().setFlag(Flags.QR);
        answer.addRecord(query.getQuestion(), Section.QUESTION);
        answer.addRecord(new ARecord(query.getQuestion().getName(), DClass.IN, ttl, InetAddress.getByName(address)), Section.ANSWER);
        return answer;
    }
}
