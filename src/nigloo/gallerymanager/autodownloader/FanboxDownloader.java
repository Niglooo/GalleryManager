package nigloo.gallerymanager.autodownloader;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import nigloo.tool.gson.JsonHelper;

public class FanboxDownloader extends Downloader
{
	private static final String HEADERS_KEY = "headers";

	@Override
	public DownloaderType getType()
	{
		return DownloaderType.FANBOX;
	}

	@Override
	protected void onStartDownload(DownloadSession session) throws Exception
	{
		session.setExtaInfo(HEADERS_KEY, getHeaders(session));
	}
	
	@Override
	protected Iterator<Post> listPosts(DownloadSession session) throws Exception
	{
		return new FanboxPostIterator(session);
	}
	
	private class FanboxPostIterator extends BasePostIterator
	{
		private String nextPageUrl;
		private Iterator<String> postIdsIt;
		
		public FanboxPostIterator(DownloadSession session) throws Exception
		{
			super(session);
			this.nextPageUrl = "https://api.fanbox.cc/post.listCreator?creatorId=" + creatorId + "&limit=10";
			this.postIdsIt = Collections.emptyIterator();
			computeNextPost();
		}
		
		@Override
		protected Post findNextPost() throws Exception
		{
			if (postIdsIt.hasNext())
			{
				String postId = postIdsIt.next();
				
				HttpRequest request = HttpRequest.newBuilder()
				                                 .uri(new URI("https://api.fanbox.cc/post.info?postId=" + postId))
				                                 .GET()
				                                 .headers(session.getExtraInfo(HEADERS_KEY))
				                                 .build();
				JsonElement post = session.send(request, JsonHelper.httpBodyHandler())
				                          .body()
				                          .getAsJsonObject()
				                          .get("body");
				
				String postTitle = JsonHelper.followPath(post, "title");
				ZonedDateTime publishedDatetime = ZonedDateTime.parse(JsonHelper.followPath(post, "publishedDatetime"));
				
				return Post.create(postId, postTitle, publishedDatetime, post);
			}
			else if (nextPageUrl != null)
			{
				HttpRequest request = HttpRequest.newBuilder()
				                                 .uri(new URI(nextPageUrl))
				                                 .GET()
				                                 .headers(session.getExtraInfo(HEADERS_KEY))
				                                 .build();
				JsonElement response = session.send(request, JsonHelper.httpBodyHandler()).body();
				
				postIdsIt = JsonHelper.stream(JsonHelper.followPath(response, "body.items", JsonArray.class))
				                      .map(JsonElement::getAsJsonObject)
				                      .map(post -> post.get("id").getAsString())
				                      .iterator();
				nextPageUrl = JsonHelper.followPath(response, "body.nextUrl");
				// Recursive call to handle cases where postIdsIt would be empty
				return findNextPost();
			}
			else
			{
				return null;
			}
		}
	}
	
	@Override
	protected CompletableFuture<List<PostImage>> listImages(DownloadSession session, Post post)
	{
		JsonObject jPost = (JsonObject) post.extraInfo();
		
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
				jImages = new JsonArray(0);
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
		JsonObject jPost = (JsonObject) post.extraInfo();
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
				jFiles = new JsonArray(0);
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
			"accept", "application/json, text/plain, */*",
			"accept-encoding", "gzip, deflate",
			"accept-language", "fr-FR,fr;q=0.9",
			"cookie", session.getSecret("fanbox.cookie"),
			"origin", "https://" + creatorId + ".fanbox.cc",
			"referer", "https://" + creatorId + ".fanbox.cc/",
			"sec-ch-ua", "\" Not A;Brand\";v=\"99\", \"Chromium\";v=\"90\", \"Google Chrome\";v=\"90\"",
			"sec-ch-ua-mobile", "?0",
			"sec-fetch-dest", "empty",
			"sec-fetch-mode", "cors",
			"sec-fetch-site", "same-site",
			"user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.93 Safari/537.36" };
		// @formatter:on
	}
}
