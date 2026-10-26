package org.almond_eye_bot;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.almond_eye_bot.client.TwitterClient;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.*;
import org.springframework.http.MediaType;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
class TwitterClientTests {
 TwitterClient api;MockRestServiceServer server;
 @BeforeEach void setup(){api=new TwitterClient();ReflectionTestUtils.setField(api,"token","fake");server=MockRestServiceServer.bindTo((RestTemplate)ReflectionTestUtils.getField(api,"rest")).build();}
 @ParameterizedTest @ValueSource(strings={"https://x.com/Example","https://twitter.com/Example/","https://x.com/Example?s=21"})
 void profileLinks(String link){assertEquals("Example",TwitterClient.username(link));}
 @ParameterizedTest @ValueSource(strings={"http://x.com/Example","https://x.com.evil/Example","https://x.com@evil.com/Example","https://x.com/Example/status/123","https://x.com/home","https://127.0.0.1/Example","https://x.com/Example/followers"})
 void invalidLinks(String link){assertThrows(IllegalArgumentException.class,()->TwitterClient.username(link));}
 @Test void readsOfficialLookupAndLatestByNumericId(){
  server.expect(requestTo("https://api.x.com/2/users/by/username/Example")).andExpect(header("Authorization","Bearer fake"))
   .andRespond(withSuccess("{\"data\":{\"id\":\"900\",\"username\":\"Example\"}}",MediaType.APPLICATION_JSON));
  server.expect(requestTo("https://api.x.com/2/users/900/tweets?max_results=5&tweet.fields=created_at,note_tweet&since_id=8"))
   .andRespond(withSuccess("{\"data\":[{\"id\":\"9\",\"text\":\"old\"},{\"id\":\"10\",\"text\":\"short\",\"note_tweet\":{\"text\":\"full\"}}]}",MediaType.APPLICATION_JSON));
  assertEquals("900",api.lookup("Example").id());var p=api.latest("900","8").orElseThrow();assertEquals("10",p.id());assertEquals("full",p.text());server.verify();
 }
 @Test void emptyTimelineIsNotAnApiError(){server.expect(anything()).andRespond(withSuccess("{\"meta\":{\"result_count\":0}}",MediaType.APPLICATION_JSON));assertTrue(api.latest("900","").isEmpty());server.verify();}
 @Test void rateLimitStopsSubsequentNetworkRequests(){server.expect(anything()).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));assertThrows(IllegalStateException.class,()->api.latest("900",""));assertThrows(IllegalStateException.class,()->api.latest("900",""));server.verify();}
 @Test void apiErrorsDoNotBecomeEmptyTimeline(){server.expect(anything()).andRespond(withSuccess("{\"errors\":[{\"detail\":\"secret upstream details\"}]}",MediaType.APPLICATION_JSON));var e=assertThrows(IllegalStateException.class,()->api.latest("900",""));assertFalse(e.getMessage().contains("secret"));server.verify();}
}
