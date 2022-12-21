package nigloo.gallerymanager.autodownloader;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import nigloo.tool.gson.JsonHelper;

public class TwitterDownloader extends Downloader
{
	private static final DateTimeFormatter DATE_TIME_FORMATTER = twitterDateTimeFormater();
	private static DateTimeFormatter twitterDateTimeFormater()
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
	private static final int PAGE_SIZE = 20;
	
	private static final String HEADERS_KEY = "headers";
	
	@SuppressWarnings("unused")
	private TwitterDownloader()
	{
		super();
	}
	
	public TwitterDownloader(String creatorId)
	{
		super(creatorId);
	}
	
	@Override
	protected void onStartDownload(DownloadSession session) throws Exception
	{
		session.setExtaInfo(HEADERS_KEY, getHeaders(session));
	}
	
	@Override
	protected Iterator<Post> listPosts(DownloadSession session) throws Exception
	{
		return new TwitterPostIterator(session);
	}
	
	private class TwitterPostIterator extends BasePostIterator
	{
		private final String restId;
		
		private String nextPageUrl;
		private String currentCursor;
		private Iterator<JsonElement> postsIt;
		
		public TwitterPostIterator(DownloadSession session) throws Exception
		{
			super(session);
			
			String url = "https://twitter.com/i/api/graphql/G07SmTUd0Mx7qy3Az_b52w/UserByScreenNameWithoutResults?variables=%7B%22screen_name%22%3A%22"
			        + creatorId
			        + "%22%2C%22withHighlightedLabel%22%3Atrue%2C%22withSuperFollowsUserFields%22%3Afalse%7D";
			HttpRequest request = HttpRequest.newBuilder()
			                                 .uri(new URI(url))
			                                 .GET()
			                                 .headers(session.getExtraInfo(HEADERS_KEY))
			                                 .build();
			JsonElement response = session.send(request, JsonHelper.httpBodyHandler()).body();
			
			this.restId = JsonHelper.followPath(response, "data.user.rest_id");
			this.nextPageUrl = listTweetUrl(restId, null);
			this.currentCursor = null;
			this.postsIt = Collections.emptyIterator();
			
			computeNextPost();
		}
		
		@Override
		protected Post findNextPost() throws Exception
		{
			if (postsIt.hasNext())
			{
				JsonElement item = postsIt.next();
				JsonObject post = JsonHelper.followPath(item,
				                                        "content.itemContent.tweet_results.result",
				                                        JsonObject.class);
				
				if (post == null)
				{
					if ("Bottom".equals(JsonHelper.followPath(item, "content.cursorType")))
					{
						String cursor = JsonHelper.followPath(item, "content.value");
						if (cursor.equals(currentCursor))
							return null;
						
						nextPageUrl = listTweetUrl(restId, cursor);
						currentCursor = cursor;
					}
					return findNextPost();
				}
				
				if ("TweetTombstone".equals(JsonHelper.followPath(post, "__typename")))
					return findNextPost();
				
				String postId = JsonHelper.followPath(post, "rest_id");
				ZonedDateTime publishedDatetime = DATE_TIME_FORMATTER.parse(JsonHelper.followPath(post,
				                                                                                  "legacy.created_at"),
				                                                            ZonedDateTime::from);
				JsonArray images = JsonHelper.followPath(post, "legacy.entities.media", JsonArray.class);
				
				return Post.create(postId, postId, publishedDatetime, images);
			}
			else if (nextPageUrl != null)
			{
				HttpRequest request = HttpRequest.newBuilder()
				                                 .uri(new URI(nextPageUrl))
				                                 .GET()
				                                 .headers(session.getExtraInfo(HEADERS_KEY))
				                                 .build();
				JsonElement response = session.send(request, JsonHelper.httpBodyHandler()).body();
				
				postsIt = JsonHelper.followPath(response,
				                                "data.user.result.timeline.timeline.instructions[0].entries",
				                                JsonArray.class)
				                    .iterator();
				
				nextPageUrl = null;
				
				// Recursive call to properly handle cursors.
				return findNextPost();
			}
			else
			{
				return null;
			}
		}
		
		private String listTweetUrl(String userId, String cursor)
		{
			// @formatter:off
			String variables =
			"{" +
				"\"userId\":\"" + userId + "\"," +
				"\"count\":" + PAGE_SIZE + "," + 
				(cursor == null ? "" : "\"cursor\":\"" + cursor + "\",") +
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

	@Override
	protected CompletableFuture<List<PostImage>> listImages(DownloadSession session, Post post)
	{
		JsonArray images = (JsonArray) post.extraInfo();
		
		return CompletableFuture.completedFuture(JsonHelper.stream(images).map(image ->
		{
			String url = JsonHelper.followPath(image, "media_url_https");
			String imageFilename = url.substring(url.lastIndexOf('/') + 1);
			String imageId = imageFilename.substring(0, imageFilename.lastIndexOf('.'));
			
			return PostImage.create(imageId, imageFilename, url, null);
		}).toList());
	}

	@Override
	protected String[] getHeardersForImageDownload(DownloadSession session, PostImage image)
	{
		return session.getExtraInfo(HEADERS_KEY);
	}
	
	private String[] getHeaders(DownloadSession session)
	{
		// @formatter:off
		//TODO automatic login? (at least retrieve authorization and x-csrf-token)
		return new String[] {
			"accept", "*/*",
			"accept-encoding", "gzip, deflate",
			"accept-language", "fr-FR,fr;q=0.9",
			"authorization", session.getSecret("twitter.authorization"),
			"content-type", "application/json",
			"cookie", session.getSecret("twitter.cookie"),
			"dnt", "1",
			"referer", "https://twitter.com",
			"sec-ch-ua", "\" Not;A Brand\";v=\"99\", \"Google Chrome\";v=\"91\", \"Chromium\";v=\"91\"",
			"sec-ch-ua-mobile", "?0",
			"sec-fetch-dest", "empty",
			"sec-fetch-mode", "cors",
			"sec-fetch-site", "same-origin",
			"user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
			"x-csrf-token", session.getSecret("twitter.x-csrf-token"),
			"x-twitter-active-user", "yes",
			"x-twitter-auth-type", "OAuth2Session",
			"x-twitter-client-language", "fr"
		};
		// @formatter:on
	}
}
