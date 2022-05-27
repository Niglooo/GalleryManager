package nigloo.gallerymanager.autodownloader;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import nigloo.tool.gson.JsonHelper;

public class PixivDownloader extends Downloader
{
	@SuppressWarnings("unused")
	private PixivDownloader()
	{
		super();
	}
	
	public PixivDownloader(String creatorId)
	{
		super(creatorId);
	}
	
	@Override
	protected void doDownload(DownloadSession session) throws Exception
	{
		String cookie = session.getSecret("pixiv.cookie");
		
		final Collection<CompletableFuture<?>> postsDownloads = new ArrayList<>();
		
		HttpRequest request;
		JsonElement response;
		
		String[] headers = getHeaders(cookie);
		
		request = HttpRequest.newBuilder()
		                     .uri(new URI("https://www.pixiv.net/ajax/user/" + creatorId + "/profile/all?lang=en"))
		                     .GET()
		                     .headers(headers)
		                     .build();
		response = session.send(request, JsonHelper.httpBodyHandler()).body();
		
		List<String> postIds = JsonHelper.followPath(response, "body.illusts", JsonObject.class)
		                                 .keySet()
		                                 .stream()
		                                 .toList();
		
		final int POST_PAGE_SIZE = 20;
		JsonObject posts = null;
		int offset = 0;
		
		for (String postId : postIds)
		{
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
				request = HttpRequest.newBuilder().uri(new URI(url)).GET().headers(headers).build();
				response = session.send(request, JsonHelper.httpBodyHandler()).body();
				
				posts = JsonHelper.followPath(response, "body.works", JsonObject.class);
			}
			
			JsonElement post = posts.get(postId);
			
			String postTitle = JsonHelper.followPath(post, "title");
			ZonedDateTime publishedDatetime = ZonedDateTime.parse(JsonHelper.followPath(post, "createDate"));
			
			if (session.stopCheckingPost(publishedDatetime))
				break;
			
			request = HttpRequest.newBuilder()
			                     .uri(new URI("https://www.pixiv.net/ajax/illust/" + postId
			                             + "?ref=https%3A%2F%2Fwww.pixiv.net%2Fusers%2F" + creatorId
			                             + "%2Fillustrations&lang=en"))
			                     .GET()
			                     .headers(headers)
			                     .build();
			response = session.send(request, JsonHelper.httpBodyHandler()).body();
			JsonArray jTags = JsonHelper.followPath(response, "body.tags.tags", JsonArray.class);
			Collection<String> tags = new ArrayList<>(jTags.size());
			for (JsonElement jTag : jTags)
			{
				String tag = JsonHelper.followPath(jTag, "translation.en");
				if (tag == null)
					tag = JsonHelper.followPath(jTag, "tag");
				tags.add(tag);
			}
			
			// Load post images
			request = HttpRequest.newBuilder()
			                     .uri(new URI("https://www.pixiv.net/ajax/illust/" + postId + "/pages?lang=en"))
			                     .GET()
			                     .headers(headers)
			                     .build();
			response = session.send(request, JsonHelper.httpBodyHandler()).body();
			
			JsonArray images = JsonHelper.followPath(response, "body", JsonArray.class);
			
			Collection<CompletableFuture<?>> imagesDownloads = new ArrayList<>();
			
			int imageNumber = 1;
			for (JsonElement image : images)
			{
				String url = JsonHelper.followPath(image, "urls.original");
				String imageFilename = url.substring(url.lastIndexOf('/') + 1);
				String imageId = imageFilename.substring(0, imageFilename.lastIndexOf('.'));
				
				imagesDownloads.add(downloadImage(session,
				                                  url,
				                                  headers,
				                                  postId,
				                                  imageId,
				                                  publishedDatetime,
				                                  postTitle,
				                                  imageNumber,
				                                  imageFilename,
				                                  tags));
				imageNumber++;
			}
			
			postsDownloads.add(CompletableFuture.allOf(imagesDownloads.toArray(CompletableFuture[]::new))
			                                    .thenRun(() -> session.onPostDownloaded(publishedDatetime)));
		}
		
		CompletableFuture.allOf(postsDownloads.toArray(CompletableFuture[]::new)).join();
		
		session.saveLastPublishedDatetime();
	}
	
	private String[] getHeaders(String cookie)
	{
		// @formatter:off
		return new String[] {
			"accept", "application/json, text/plain, */*",
			"accept-encoding", "gzip, deflate",
			"accept-language", "fr-FR,fr;q=0.9",
			"cookie", cookie,
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
