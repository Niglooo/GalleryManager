package nigloo.gallerymanager.autodownloader;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.ZonedDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import nigloo.tool.Utils;
import nigloo.tool.gson.JsonHelper;

public class PixivDownloader extends Downloader
{
	private static final String HEADERS_KEY = "headers";

	@Override
	public DownloaderType getType()
	{
		return DownloaderType.PIXIV;
	}

	@Override
	protected void onStartDownload(DownloadSession session) throws Exception
	{
		session.setExtaInfo(HEADERS_KEY, getHeaders(session));
	}
	
	@Override
	protected Iterator<Post> listPosts(DownloadSession session) throws Exception
	{
		return new PixivPostIterator(session);
	}
	
	private class PixivPostIterator extends BasePostIterator
	{
		private static final int POST_PAGE_SIZE = 20;
		
		private final List<String> postIds;
		private final Iterator<String> postIdsIt;
		private JsonObject posts;
		private int offset;
		
		public PixivPostIterator(DownloadSession session) throws Exception
		{
			super(session);
			HttpRequest request = HttpRequest.newBuilder()
			                                 .uri(new URI("https://www.pixiv.net/ajax/user/" + creatorId
			                                         + "/profile/all?lang=en"))
			                                 .GET()
			                                 .headers(session.getExtraInfo(HEADERS_KEY))
			                                 .build();
			JsonElement response = session.send(request, JsonHelper.httpBodyHandler()).body();
			
			this.postIds = JsonHelper.followPath(response, "body.illusts", JsonObject.class).keySet().stream().toList();
			this.postIdsIt = this.postIds.iterator();
			this.posts = null;
			this.offset = 0;
			
			computeNextPost();
		}
		
		@Override
		protected Post findNextPost() throws Exception
		{
			if (!postIdsIt.hasNext())
				return null;
			
			String postId = postIdsIt.next();
			
			// Load posts by batch of POST_PAGE_SIZE
			if (posts == null || !posts.has(postId))
			{
				if (posts != null)
					offset += POST_PAGE_SIZE;
				
				String url = "https://www.pixiv.net/ajax/user/"
				        + creatorId + "/profile/illusts?" + postIds.stream()
				                                                   .skip(offset)
				                                                   .limit(POST_PAGE_SIZE)
				                                                   .map(id -> "ids%5B%5D=" + id)
				                                                   .collect(Collectors.joining("&"))
				        + "&work_category=illust&is_first_page=0&lang=en";
				HttpRequest request = HttpRequest.newBuilder().uri(new URI(url)).GET().headers(session.getExtraInfo(HEADERS_KEY)).build();
				JsonElement response = session.send(request, JsonHelper.httpBodyHandler()).body();
				
				posts = JsonHelper.followPath(response, "body.works", JsonObject.class);
			}
			
			JsonElement post = posts.get(postId);
			
			String postTitle = JsonHelper.followPath(post, "title");
			ZonedDateTime publishedDatetime = ZonedDateTime.parse(JsonHelper.followPath(post, "createDate"));
			
			return Post.create(postId, postTitle, publishedDatetime, null);
		}
	}
	
	@Override
	protected CompletableFuture<List<PostImage>> listImages(DownloadSession session, Post post) throws Exception
	{
		// Load post tags
		HttpRequest tagRequest = HttpRequest.newBuilder()
		                                    .uri(new URI("https://www.pixiv.net/ajax/illust/" + post.id()
		                                            + "?ref=https%3A%2F%2Fwww.pixiv.net%2Fusers%2F" + creatorId
		                                            + "%2Fillustrations&lang=en"))
		                                    .GET()
		                                    .headers(session.getExtraInfo(HEADERS_KEY))
		                                    .build();
		CompletableFuture<HttpResponse<JsonElement>> tagsCf = session.sendAsync(tagRequest, JsonHelper.httpBodyHandler());
		
		// Load post images
		HttpRequest imagesResquest = HttpRequest.newBuilder()
		                                        .uri(new URI("https://www.pixiv.net/ajax/illust/" + post.id()
		                                                + "/pages?lang=en"))
		                                        .GET()
		                                        .headers(session.getExtraInfo(HEADERS_KEY))
		                                        .build();
		CompletableFuture<HttpResponse<JsonElement>>  imagesCf = session.sendAsync(imagesResquest, JsonHelper.httpBodyHandler());
		
		return tagsCf.thenCombine(imagesCf, (tagsResponse, imagesResponse) ->
		{
			List<String> tags = JsonHelper.stream(JsonHelper.followPath(tagsResponse.body(),
			                                                            "body.tags.tags",
			                                                            JsonArray.class))
			                              .map(jTag -> Utils.coalesce(JsonHelper.followPath(jTag, "translation.en"),
			                                                          JsonHelper.followPath(jTag, "tag")))
			                              .toList();
			return JsonHelper.stream(JsonHelper.followPath(imagesResponse.body(), "body", JsonArray.class)).map(image ->
			{
				String url = JsonHelper.followPath(image, "urls.original");
				String imageFilename = url.substring(url.lastIndexOf('/') + 1);
				String imageId = imageFilename.substring(0, imageFilename.lastIndexOf('.'));
				
				return PostImage.create(imageId, imageFilename, url, tags);
			}).toList();
		});
	}

	@Override
	protected String[] getHeadersForImageDownload(DownloadSession session, PostImage image)
	{
		return session.getExtraInfo(HEADERS_KEY);
	}
	
	private String[] getHeaders(DownloadSession session)
	{
		// @formatter:off
		return new String[] {
			"accept", "application/json, text/plain, */*",
			"accept-encoding", "gzip, deflate",
			"accept-language", "fr-FR,fr;q=0.9",
			"cookie", session.getSecret("pixiv.cookie"),
			"referer", "https://www.pixiv.net/en/users/"+creatorId+"/illustrations",
			"sec-ch-ua", "\" Not;A Brand\";v=\"99\", \"Google Chrome\";v=\"91\", \"Chromium\";v=\"91\"",
			"sec-ch-ua-mobile", "?0",
			"sec-fetch-dest", "empty",
			"sec-fetch-mode", "cors",
			"sec-fetch-site", "same-origin",
			"user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.93 Safari/537.36"};
		// @formatter:on
	}
}
