package nigloo.gallerymanager.autodownloader;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import nigloo.tool.Utils;
import nigloo.tool.gson.JsonHelper;

public class FanboxDownloader extends Downloader
{
	private static final String HEADERS_KEY = "headers";
	private static final String POSTS_DETAIL_CACHE_KEY = "posts-detail";

	@Override
	public DownloaderType getType()
	{
		return DownloaderType.FANBOX;
	}

	@Override
	protected void onStartDownload(DownloadSession session) throws Exception
	{
		session.setExtaInfo(HEADERS_KEY, getHeaders(session));
		session.setExtaInfo(POSTS_DETAIL_CACHE_KEY, new ConcurrentHashMap<>());
	}
	
	@Override
	protected Iterator<Post> listPosts(DownloadSession session) throws Exception
	{
		return new FanboxPostIterator(session);
	}
	
	private class FanboxPostIterator extends BasePostIterator
	{
		private final Iterator<String> pagesUrls;
		private Iterator<Post> postIt;
		
		public FanboxPostIterator(DownloadSession session) throws Exception
		{
			super(session);
			try
			{
				HttpRequest request = HttpRequest
						.newBuilder()
						.uri(new URI("https://api.fanbox.cc/post.paginateCreator?creatorId=" + creatorId))
						.GET()
						.headers(session.getExtraInfo(HEADERS_KEY))
						.build();
				this.pagesUrls = JsonHelper.stream(JsonHelper.followPath(
						session.send(request, JsonHelper.httpBodyHandler()).body(),
						"body",
						JsonArray.class))
						   .map(JsonElement::getAsString)
						   .iterator();
			}
			catch (HttpException e)
			{
				if (e.getStatusCode() == 403)
					throw new DownloaderSessionExpiredException();
				else
					throw e;
			}
			this.postIt = Collections.emptyIterator();
			computeNextPost();
		}
		
		@Override
		protected Post findNextPost() throws Exception
		{
			if (postIt.hasNext())
			{
				return postIt.next();
			}
			else if (pagesUrls.hasNext())
			{
				String nextPageUrl = pagesUrls.next();
				HttpRequest request = HttpRequest.newBuilder()
												 .uri(new URI(nextPageUrl))
												 .GET()
												 .headers(session.getExtraInfo(HEADERS_KEY))
												 .build();
				JsonElement response = session.send(request, JsonHelper.httpBodyHandler()).body();

				postIt = JsonHelper.stream(JsonHelper.followPath(response, "body", JsonArray.class))
									  .map(post -> {
										  String postId = JsonHelper.followPath(post, "id");
										  String postTitle = JsonHelper.followPath(post, "title");
										  ZonedDateTime publishedDatetime = ZonedDateTime.parse(JsonHelper.followPath(post, "publishedDatetime"));

										  return Post.create(postId, postTitle, publishedDatetime, null);
									  })
									  .iterator();

				// Recursive call to handle cases where postIt would be empty
				return findNextPost();
			}
			else
			{
				return null;
			}
		}
	}

	private JsonObject getPostDetail(DownloadSession session, Post post)
	{
		Map<String, JsonObject> cache = session.getExtraInfo(POSTS_DETAIL_CACHE_KEY);
		return cache.computeIfAbsent(post.id(), postId -> {
			try
			{
				HttpRequest request = HttpRequest.newBuilder()
												 .uri(new URI("https://api.fanbox.cc/post.info?postId=" + post.id()))
												 .GET()
												 .headers(session.getExtraInfo(HEADERS_KEY))
												 .build();
				JsonObject jPost = JsonHelper.followPath(session.send(request, JsonHelper.httpBodyHandler()).body()
						, "body", JsonObject.class);
				return jPost;
			}
			catch (URISyntaxException | IOException | InterruptedException e) {
				throw Utils.asRunTimeException(e);
			}
		});
	}
	
	@Override
	protected CompletableFuture<List<PostImage>> listImages(DownloadSession session, Post post)
	{
		JsonObject jPost = getPostDetail(session, post);
		
		JsonArray jImages = JsonHelper.followPath(jPost, "body.images", JsonArray.class);
		if (jImages == null)
		{
			JsonObject imageMap = JsonHelper.followPath(jPost, "body.imageMap", JsonObject.class);
			if (imageMap != null)
			{
				jImages = new JsonArray(imageMap.size());
				JsonArray blocks = JsonHelper.followPath(jPost, "body.blocks", JsonArray.class);
				for (JsonElement block : blocks)
				{
					String type = JsonHelper.followPath(block, "type");
					if ("image".equals(type))
					{
						String imageId = JsonHelper.followPath(block, "imageId");
						jImages.add(imageMap.get(imageId));
					}
				}
			}
			else
			{
				jImages = new JsonArray(0);
			}
		}
		
		List<String> tags = JsonHelper.stream(JsonHelper.followPath(jPost, "tags", JsonArray.class)).map(JsonElement::getAsString).toList();
		
		List<PostImage> images = new ArrayList<>(jImages.size());
		for (JsonElement jImage : jImages)
		{
			String imageId = JsonHelper.followPath(jImage, "id");
			String url = JsonHelper.followPath(jImage, "originalUrl");
			
			String imageFilename = url.substring(url.lastIndexOf('/') + 1);
			
			images.add(PostImage.create(imageId, imageFilename, url, tags));
		}
		
		return CompletableFuture.completedFuture(images);
	}
	
	@Override
	protected CompletableFuture<List<PostFile>> listFiles(DownloadSession session, Post post) throws Exception
	{
		JsonObject jPost = getPostDetail(session, post);
		JsonArray jFiles = JsonHelper.followPath(jPost, "body.files", JsonArray.class);
		if (jFiles == null)
		{
			JsonObject fileMap = JsonHelper.followPath(jPost, "body.fileMap", JsonObject.class);
			if (fileMap != null)
			{
				jFiles = new JsonArray(fileMap.size());
				JsonArray blocks = JsonHelper.followPath(jPost, "body.blocks", JsonArray.class);
				for (JsonElement block : blocks)
				{
					String type = JsonHelper.followPath(block, "type");
					if ("file".equals(type))
					{
						String fileId = JsonHelper.followPath(block, "fileId");
						jFiles.add(fileMap.get(fileId));
					}
				}
			}
			else
			{
				jFiles = new JsonArray(0);
			}
		}
		
		List<String> tags = JsonHelper.stream(JsonHelper.followPath(jPost, "tags", JsonArray.class)).map(JsonElement::getAsString).toList();
		
		ArrayList<PostFile> files = new ArrayList<>(jFiles.size());
		for (JsonElement jfile : jFiles)
		{
			String fileId = JsonHelper.followPath(jfile, "id");
			String url = JsonHelper.followPath(jfile, "url");
			String fileNameWithoutExtention = JsonHelper.followPath(jfile, "name");
			String fileExtention = JsonHelper.followPath(jfile, "extension");
			
			String filename = fileNameWithoutExtention+'.'+fileExtention;
			
			files.add(PostFile.create(fileId, filename, url, tags));
		}
		
		return CompletableFuture.completedFuture(files);
	}
	
	@Override
	protected String[] getHeadersForImageDownload(DownloadSession session, PostImage image)
	{
		return getHeaders(session);
	}
	
	@Override
	protected String[] getHeadersForFileDownload(DownloadSession session, PostFile image)
	{
		return getHeaders(session);
	}
	
	private String[] getHeaders(DownloadSession session)
	{
		// @formatter:off
		return new String[] {
			"Accept", "application/json, text/plain, */*",
			"Accept-Encoding", "gzip, deflate",
			"Accept-Language", "fr-FR,fr;q=0.9",
			"Cookie", session.getSecret("fanbox.cookie"),
			"Origin", "https://www.fanbox.cc",
			"Priority", "u=1, i",
			"Referer", "https://www.fanbox.cc/", 
			"Sec-Ch-Ua", "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"126\", \"Brave\";v=\"126\"",
			"Sec-Ch-Ua-Mobile", "?0",
			"Sec-Ch-Ua-Platform", "\"Windows\"",
			"Sec-Fetch-Dest", "empty",
			"Sec-Fetch-Mode", "cors",
			"Sec-Fetch-Site", "same-site",
			"Sec-Gpc", "1",
			"User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36" };
		// @formatter:on
	}
}
