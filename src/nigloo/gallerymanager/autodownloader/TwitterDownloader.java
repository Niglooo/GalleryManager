package nigloo.gallerymanager.autodownloader;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.mizosoft.methanol.MoreBodyHandlers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import nigloo.gallerymanager.AsyncPools;
import nigloo.tool.StrongReference;
import nigloo.tool.gson.JsonHelper;

public class TwitterDownloader extends BaseDownloader
{
	private static final Logger LOGGER = LogManager.getLogger(TwitterDownloader.class);
	private static final DateTimeFormatter DATE_TIME_FORMATTER = twitterDateTimeFormmater();
	private static final int PAGE_SIZE = 20;
	
	@SuppressWarnings("unused")
	private TwitterDownloader()
	{
		super();
	}
	
	public TwitterDownloader(String creatorId)
	{
		super(creatorId);
	}
	
	private static DateTimeFormatter twitterDateTimeFormmater()
	{
		// Exemple: Sat Jul 10 10:57:00 +0000 2021
		return new DateTimeFormatterBuilder().parseStrict()
		                                     .appendText(ChronoField.DAY_OF_WEEK, TextStyle.SHORT)
		                                     .appendLiteral(' ')
		                                     .appendText(ChronoField.MONTH_OF_YEAR, TextStyle.SHORT)
		                                     .appendLiteral(' ')
		                                     .appendValue(ChronoField.DAY_OF_MONTH, 2)
		                                     .appendLiteral(' ')
		                                     .appendValue(ChronoField.HOUR_OF_DAY, 2)
		                                     .appendLiteral(':')
		                                     .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
		                                     .appendLiteral(':')
		                                     .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
		                                     .appendLiteral(' ')
		                                     .appendOffset("+HHMM", "+0000")
		                                     .appendLiteral(' ')
		                                     .appendValue(ChronoField.YEAR, 4)
		                                     .toFormatter(Locale.US);
	}
	
	@Override
	public void download(Properties secrets, boolean checkAllPost) throws Exception
	{
		LOGGER.debug(creatorId);
		LOGGER.debug(imagePathPattern);
		
		final StrongReference<ZonedDateTime> currentMostRecentPost = initCurrentMostRecentPost();
		
		final Semaphore maxConcurrentStreams = new Semaphore(10);// TODO init with max_concurrent_streams from http2
		final Collection<CompletableFuture<?>> imagesDownload = new ArrayList<>();
		
		final HttpClient httpClient = HttpClient.newBuilder()
		                                        .followRedirects(Redirect.NORMAL)
		                                        .executor(AsyncPools.HTTP_REQUEST)
		                                        .build();
		String url;
		HttpRequest request;
		HttpResponse<?> response;
		JsonObject parsedResponse;
		
		String[] headers = getHeaders(secrets);
		
		url = "https://twitter.com/i/api/graphql/G07SmTUd0Mx7qy3Az_b52w/UserByScreenNameWithoutResults?variables=%7B%22screen_name%22%3A%22"
		        + creatorId + "%22%2C%22withHighlightedLabel%22%3Atrue%2C%22withSuperFollowsUserFields%22%3Afalse%7D";
		request = HttpRequest.newBuilder().uri(new URI(url)).GET().headers(headers).build();
		maxConcurrentStreams.acquire();
		response = httpClient.send(request, MoreBodyHandlers.decoding(BodyHandlers.ofString()));
		print(response, PrintOption.REQUEST_URL, PrintOption.STATUS_CODE);
		maxConcurrentStreams.release();
		parsedResponse = JsonParser.parseString(response.body().toString()).getAsJsonObject();
		
		String restId = JsonHelper.followPath(parsedResponse, "data.user.rest_id", String.class);
		
		String currentUrl = listTweetUrl(restId, null);
		String previousCursor = null;
		
		mainloop:
		while (currentUrl != null)
		{
			request = HttpRequest.newBuilder().uri(new URI(currentUrl)).GET().headers(headers).build();
			maxConcurrentStreams.acquire();
			response = httpClient.send(request, MoreBodyHandlers.decoding(BodyHandlers.ofString()));
			print(response, PrintOption.REQUEST_URL, PrintOption.STATUS_CODE);
			maxConcurrentStreams.release();
			parsedResponse = JsonParser.parseString(response.body().toString()).getAsJsonObject();
			
			JsonArray posts = JsonHelper.followPath(parsedResponse,
			                                        "data.user.result.timeline.timeline.instructions[0].entries",
			                                        JsonArray.class);
			
			currentUrl = null;
			
			for (JsonElement item : posts)
			{
				JsonObject post = JsonHelper.followPath(item,
				                                        "content.itemContent.tweet_results.result",
				                                        JsonObject.class);
				
				if (post == null)
				{
					if ("Bottom".equals(JsonHelper.followPath(item, "content.cursorType", String.class)))
					{
						String cursor = JsonHelper.followPath(item, "content.value", String.class);
						if (cursor.equals(previousCursor))
							break;
						
						currentUrl = listTweetUrl(restId, cursor);
						previousCursor = cursor;
					}
					continue;
				}
				
				String postId = JsonHelper.followPath(post, "rest_id", String.class);
				ZonedDateTime publishedDatetime = DATE_TIME_FORMATTER.parse(JsonHelper.followPath(post,
				                                                                                  "legacy.created_at",
				                                                                                  String.class),
				                                                            ZonedDateTime::from);
				
				updateCurrentMostRecentPost(currentMostRecentPost, publishedDatetime);
				if (dontCheckPost(publishedDatetime, checkAllPost))
					break mainloop;
				
				JsonArray images = JsonHelper.followPath(post, "legacy.entities.media", JsonArray.class);
				if (images == null)
					continue;
				
				int imageNumber = 1;
				for (JsonElement image : images)
				{
					url = JsonHelper.followPath(image, "media_url_https", String.class);
					String imageFilename = url.substring(url.lastIndexOf('/') + 1);
					String imageId = imageFilename.substring(0, imageFilename.lastIndexOf('.'));
					
					imagesDownload.add(downloadImage(httpClient,
					                                 url,
					                                 headers,
					                                 maxConcurrentStreams,
					                                 postId,
					                                 imageId,
					                                 publishedDatetime,
					                                 postId,
					                                 imageNumber,
					                                 imageFilename));
					imageNumber++;
				}
			}
		}
		
		CompletableFuture.allOf(imagesDownload.toArray(CompletableFuture[]::new)).join();
		
		saveCurrentMostRecentPost(currentMostRecentPost);
	}
	
	private String[] getHeaders(Properties secrets)
	{
		// @formatter:off
		//TODO automatic login? (at least retrieve authorization and x-csrf-token)
		return new String[] {
			"accept", "*/*",
			"accept-encoding", "gzip, deflate",
			"accept-language", "fr-FR,fr;q=0.9",
			"authorization", secrets.getProperty("twitter.authorization"),
			"content-type", "application/json",
			"cookie", secrets.getProperty("twitter.cookie"),
			"dnt", "1",
			"referer", "https://twitter.com",
			"sec-ch-ua", "\" Not;A Brand\";v=\"99\", \"Google Chrome\";v=\"91\", \"Chromium\";v=\"91\"",
			"sec-ch-ua-mobile", "?0",
			"sec-fetch-dest", "empty",
			"sec-fetch-mode", "cors",
			"sec-fetch-site", "same-origin",
			"user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
			"x-csrf-token", secrets.getProperty("twitter.x-csrf-token"),
			"x-twitter-active-user", "yes",
			"x-twitter-auth-type", "OAuth2Session",
			"x-twitter-client-language", "fr"
		};
		// @formatter:on
	}
	
	private String listTweetUrl(String userId, String cursor)
	{
		// @formatter:off
		String variables =
		"{" +
			"\"userId\":\"" + userId + "\"," +
			"\"count\":" + PAGE_SIZE + "," + (cursor == null ? "" : "\"cursor\":\"" + cursor + "\",") +
			"\"withHighlightedLabel\":false," +
			"\"withTweetQuoteCount\":false," +
			"\"includePromotedContent\":false," +
			"\"withTweetResult\":true," +
			"\"withReactions\":false," +
			"\"withSuperFollowsTweetFields\":false," +
			"\"withSuperFollowsUserFields\":false," +
			"\"withUserResults\":false," +
			"\"withClientEventToken\":false," +
			"\"withBirdwatchNotes\":false," +
			"\"withBirdwatchPivots\":false," +
			"\"withVoice\":false" +
		"}";
		// @formatter:on
		return "https://twitter.com/i/api/graphql/-ClzyWY3kWmGS8BSPHgv8w/UserMedia?variables="
		        + URLEncoder.encode(variables, StandardCharsets.UTF_8);
	}
}
