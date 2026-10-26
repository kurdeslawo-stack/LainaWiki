package pl.laina.reforge.wiki;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WikiPageIndexTest {
    @Test void readsAllArticlesAndItemCategoryWithIndependentContinuation() throws Exception {
        var queries=new ArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api.php",exchange->{
            String query=URLDecoder.decode(exchange.getRequestURI().getRawQuery(),StandardCharsets.UTF_8);queries.add(query);
            String body;
            if(query.contains("list=allpages")) body=query.contains("apcontinue=")
                    ? "<api><query><allpages><p ns=\"0\" title=\"Ezoteryzm\"/><p ns=\"2\" title=\"User:Other\"/></allpages></query></api>"
                    : "<api><continue apcontinue=\"E z&amp;o\"/><query><allpages><p ns=\"0\" title=\"Buty Erosa\"/></allpages></query></api>";
            else body=query.contains("cmcontinue=")
                    ? "<api><query><categorymembers><cm ns=\"0\" title=\"Miecz\"/></categorymembers></query></api>"
                    : "<api><continue cmcontinue=\"next|item\"/><query><categorymembers><cm ns=\"0\" title=\"Buty Erosa\"/></categorymembers></query></api>";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        server.start();
        try(var client=HttpClient.newHttpClient()) {
            var snapshot=WikiPageIndex.fetch(client,"http://127.0.0.1:"+server.getAddress().getPort()+"/api.php",()->false);
            assertEquals(List.of("Buty Erosa","Ezoteryzm"),snapshot.articles());
            assertEquals(List.of("Buty Erosa","Miecz"),snapshot.items());
            assertEquals(4,queries.size());
            assertTrue(queries.getFirst().contains("apnamespace=0"));
            assertTrue(queries.get(1).contains("apcontinue=E z&o"));
            assertEquals(List.of("Buty Erosa","Ezoteryzm","Miecz"),WikiIndexService.mergedTitles(snapshot.articles(),snapshot.items()));
        } finally {server.stop(0);}
    }
    @Test void errorsAndInvalidContinuationCannotPublishPartialIndex() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api.php",exchange->{
            byte[] bytes="<api><continue/><query><allpages><p ns=\"0\" title=\"Partial\"/></allpages></query></api>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();
        try(var client=HttpClient.newHttpClient()) {
            assertThrows(IllegalStateException.class,()->WikiPageIndex.fetch(client,"http://127.0.0.1:"+server.getAddress().getPort()+"/api.php",()->false));
            assertThrows(InterruptedException.class,()->WikiPageIndex.fetch(client,"http://127.0.0.1:"+server.getAddress().getPort()+"/api.php",()->true));
        }finally{server.stop(0);}
    }
    @Test void articleCountDeduplicatesCatalogAndIndexWithoutConflatingDifferentRunes() {
        assertEquals(List.of("Buty Erosa","Pergamin ᛚ","Ezoteryzm","Pergamin ᚱ"),WikiIndexService.mergedTitles(
                List.of("Buty_Erosa","Pergamin ᛚ"),List.of("buty erosa","Ezoteryzm","Pergamin ᚱ")));
    }
}
