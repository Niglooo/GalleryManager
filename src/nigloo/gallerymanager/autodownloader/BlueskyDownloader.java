package nigloo.gallerymanager.autodownloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import nigloo.tool.gson.JsonHelper;
import org.apache.commons.io.FilenameUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class BlueskyDownloader extends Downloader
{
	private static final int PAGE_SIZE = 30;

	private static final String REPO_ID_KEY = "repoId";
	private static final String SERVICE_ENDPOINT_KEY = "serviceEndpoint";
	private static final String ACCESS_JWT_KEY = "accessJwt";


	@Override
	public DownloaderType getType()
	{
		return DownloaderType.BLUESKY;
	}
	
	@Override
	protected void onStartDownload(DownloadSession session) throws Exception
	{
		String url = "https://bsky.social/xrpc/com.atproto.server.createSession";

		JsonObject params = new JsonObject();
		params.addProperty("identifier", session.getSecret("bluesky.identifier"));
		params.addProperty("password", session.getSecret("bluesky.password"));
		HttpRequest request = HttpRequest.newBuilder()
										 .uri(new URI(url))
										 .POST(BodyPublishers.ofString(params.toString(), StandardCharsets.UTF_8))
										 .headers(getHeaders(session))
										 .header("content-type", "application/json")
										 .build();
		JsonElement response = session.send(request, JsonHelper.httpBodyHandler()).body();

		session.setExtaInfo(REPO_ID_KEY, JsonHelper.followPath(response, "did"));
		session.setExtaInfo(SERVICE_ENDPOINT_KEY, JsonHelper.followPath(response, "didDoc.service[0].serviceEndpoint"));
		session.setExtaInfo(ACCESS_JWT_KEY, JsonHelper.followPath(response, "accessJwt"));
	}
	
	@Override
	protected Iterator<Post> listPosts(DownloadSession session) throws Exception
	{
		return new BlueskyPostIterator(session);
	}
	
	private class BlueskyPostIterator extends BasePostIterator
	{
		private final String userId;
		
		private String nextPageUrl;
		private Iterator<JsonElement> postsIt;
		
		public BlueskyPostIterator(DownloadSession session) throws Exception
		{
			super(session);

			String url = apiUrl(session, "/xrpc/app.bsky.actor.getProfile?actor=" +
					URLEncoder.encode(creatorId + ".bsky.social", StandardCharsets.UTF_8));

			HttpRequest request = HttpRequest.newBuilder()
			                                 .uri(new URI(url))
			                                 .GET()
			                                 .headers(getHeaders(session))
			                                 .build();
			JsonElement response = session.send(request, JsonHelper.httpBodyHandler()).body();

			this.userId = JsonHelper.followPath(response, "did");
			this.nextPageUrl = listPostUrl(null);
			this.postsIt = Collections.emptyIterator();
			
			computeNextPost();
		}
		
		@Override
		protected Post findNextPost() throws Exception
		{
			if (postsIt.hasNext())
			{
				JsonElement item = postsIt.next();
				JsonObject post = JsonHelper.followPath(item, "post", JsonObject.class);
				
				String postId = JsonHelper.followPath(post, "cid");
				ZonedDateTime publishedDatetime = ZonedDateTime.parse(JsonHelper.followPath(post, "record.createdAt"));
				
				return Post.create(postId, postId, publishedDatetime, post);
			}
			else if (nextPageUrl != null)
			{
				HttpRequest request = HttpRequest.newBuilder()
												 .uri(new URI(nextPageUrl))
												 .GET()
												 .headers(getHeaders(session))
												 .build();
				JsonElement response = session.send(request, JsonHelper.httpBodyHandler()).body();

				String cursor = JsonHelper.followPath(response, "cursor");
				nextPageUrl = cursor == null ? null : listPostUrl(cursor);
				postsIt = JsonHelper.followPath(response, "feed", JsonArray.class).iterator();

				// Recursive call to properly handle empty page.
				return findNextPost();
			}
			else
			{
				return null;
			}
		}
		
		private String listPostUrl(String cursor)
		{
			// @formatter:off
			String variables =
					"actor=" + URLEncoder.encode(userId, StandardCharsets.UTF_8) + "&" +
					"filter=posts_and_author_threads&" +
					(cursor == null ? "" : "cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8) + "&") +
					"includePins=true&" +
					"limit=" + PAGE_SIZE;
			// @formatter:on
			return apiUrl(session, "/xrpc/app.bsky.feed.getAuthorFeed?" + variables);
		}
	}

	@Override
	protected CompletableFuture<List<PostImage>> listImages(DownloadSession session, Post post)
	{
		JsonArray images = JsonHelper.followPath((JsonElement) post.extraInfo(), "embed.images", JsonArray.class);
		JsonArray jTags = JsonHelper.followPath((JsonElement) post.extraInfo(), "labels", JsonArray.class);
		List<String> tags = JsonHelper.stream(jTags)
									  .map(jTag -> JsonHelper.followPath(jTag, "val"))
									  .toList();

		return CompletableFuture.completedFuture(JsonHelper.stream(images).map(image ->
		{
			String url = JsonHelper.followPath(image, "fullsize");
			String imageFilename = url.substring(url.lastIndexOf('/') + 1);
			String imageId = FilenameUtils.removeExtension(imageFilename);
			
			return PostImage.create(imageId, imageFilename, url, tags);
		}).toList());
	}

	@Override
	public boolean supportLikePost()
	{
		return true;
	}

	@Override
	protected CompletableFuture<Boolean> likePost(DownloadSession session, Post post) throws Exception
	{
		JsonObject jPost = (JsonObject) post.extraInfo();
		String like = JsonHelper.followPath(jPost, "viewer.like");

		// Post already liked
		if (like != null)
		{
			return CompletableFuture.completedFuture(null);
		}

		String url = apiUrl(session, "/xrpc/com.atproto.repo.createRecord");
		String payload = """
				{
				  "collection": "app.bsky.feed.like",
				  "repo": "{repoId}",
				  "record": {
				    "subject": {
				      "uri": "{postUri}",
				      "cid": "{postCid}"
				    },
				    "createdAt": "{createdAt}",
				    "$type": "app.bsky.feed.like"
				  }
				}
				"""
				.replace("{repoId}", session.getExtraInfo(REPO_ID_KEY))
				.replace("{postUri}", JsonHelper.followPath(jPost, "uri"))
				.replace("{postCid}", JsonHelper.followPath(jPost, "cid"))
				.replace("{createdAt}", ZonedDateTime.now().withZoneSameInstant(ZoneId.of("Z")).toString());
		HttpRequest request = HttpRequest.newBuilder()
										 .uri(new URI(url))
										 .POST(BodyPublishers.ofString(payload))
										 .headers(getHeaders(session))
										 .header("content-type", "application/json")
										 .build();

		return session.sendAsync(request, JsonHelper.httpBodyHandler())
					  .thenApply(r -> "valid".equals(JsonHelper.followPath(r.body(), "validationStatus")));
	}

	@Override
	protected String[] getHeadersForImageDownload(DownloadSession session, PostImage image)
	{
		return getHeaders(session);
	}

	private String apiUrl(DownloadSession session, String urlSuffix)
	{
		return session.getExtraInfo(SERVICE_ENDPOINT_KEY) + urlSuffix;
	}

	private String[] getHeaders(DownloadSession session)
	{
		// @formatter:off
		List<String> headers = new ArrayList<>(List.of(
				"accept", "application/json, */*",
				"accept-encoding", "gzip, deflate"
		));
		// @formatter:on
		if (session.getExtraInfo(ACCESS_JWT_KEY) != null) {
			headers.add("authorization");
			headers.add("Bearer " + session.getExtraInfo(ACCESS_JWT_KEY));
		}
		return headers.toArray(String[]::new);
	}
}
