package nigloo.gallerymanager.autodownloader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.github.mizosoft.methanol.MoreBodyHandlers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import nigloo.tool.StrongReference;
import nigloo.tool.gson.JsonHelper;

public class PixivDownloader extends BaseDownloader
{
	private static final Logger LOGGER = LogManager.getLogger(PixivDownloader.class);
	
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
	public void download(Properties secrets, boolean checkAllPost) throws Exception
	{
		String cookie = secrets.getProperty("pixiv.cookie");
		
		LOGGER.debug(creatorId);
		LOGGER.debug(imagePathPattern);
		
		final StrongReference<ZonedDateTime> currentMostRecentPost = initCurrentMostRecentPost();
		
		final Semaphore maxConcurrentStreams = new Semaphore(10);// TODO init with max_concurrent_streams from http2
		final Collection<CompletableFuture<?>> imagesDownload = Collections.synchronizedCollection(new ArrayList<>());
		
		final HttpClient httpClient = HttpClient.newBuilder()
		                                        .followRedirects(Redirect.NORMAL)
		                                        .executor(executor)
		                                        .build();
		HttpRequest request;
		HttpResponse<?> response;
		JsonObject parsedResponse;
		
		String[] headers = getHeaders(cookie);
		
		request = HttpRequest.newBuilder()
		                     .uri(new URI("https://www.pixiv.net/ajax/user/" + creatorId + "/profile/all?lang=en"))
		                     .GET()
		                     .headers(headers)
		                     .build();
		maxConcurrentStreams.acquire();
		response = httpClient.send(request, MoreBodyHandlers.decoding(BodyHandlers.ofString()));
		print(response, PrintOption.REQUEST_URL, PrintOption.STATUS_CODE);
		maxConcurrentStreams.release();
		parsedResponse = JsonParser.parseString(response.body().toString()).getAsJsonObject();
		
		List<String> postIds = JsonHelper.followPath(parsedResponse, "body.illusts", JsonObject.class)
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
				        + creatorId + "/profile/illusts?" + postIds
				                                                   .subList(offset,
				                                                            Math.min(offset + POST_PAGE_SIZE,
				                                                                     postIds.size()))
				                                                   .stream()
				                                                   .map(id -> "ids%5B%5D=" + id)
				                                                   .collect(Collectors.joining("&"))
				        + "&work_category=illust&is_first_page=0&lang=en";
				request = HttpRequest.newBuilder().uri(new URI(url)).GET().headers(headers).build();
				maxConcurrentStreams.acquire();
				response = httpClient.send(request, MoreBodyHandlers.decoding(BodyHandlers.ofString()));
				print(response, PrintOption.REQUEST_URL, PrintOption.STATUS_CODE);
				maxConcurrentStreams.release();
				parsedResponse = JsonParser.parseString(response.body().toString()).getAsJsonObject();
				
				posts = JsonHelper.followPath(parsedResponse, "body.works", JsonObject.class);
			}
			
			JsonObject post = posts.get(postId).getAsJsonObject();
			
			String postTitle = JsonHelper.followPath(post, "title", String.class);
			ZonedDateTime publishedDatetime = ZonedDateTime.parse(JsonHelper.followPath(post,
			                                                                            "createDate",
			                                                                            String.class));
			
			updateCurrentMostRecentPost(currentMostRecentPost, publishedDatetime);
			if (dontCheckPost(publishedDatetime, checkAllPost))
				break;
			
			// Load post images
			request = HttpRequest.newBuilder()
			                     .uri(new URI("https://www.pixiv.net/ajax/illust/" + postId + "/pages?lang=en"))
			                     .GET()
			                     .headers(headers)
			                     .build();
			maxConcurrentStreams.acquire();
			response = httpClient.send(request, MoreBodyHandlers.decoding(BodyHandlers.ofString()));
			print(response, PrintOption.REQUEST_URL, PrintOption.STATUS_CODE);
			maxConcurrentStreams.release();
			parsedResponse = JsonParser.parseString(response.body().toString()).getAsJsonObject();
			
			JsonArray images = JsonParser.parseString(response.body().toString())
			                             .getAsJsonObject()
			                             .getAsJsonArray("body");
			
			int imageNumber = 1;
			for (JsonElement image : images)
			{
				String url = JsonHelper.followPath(image, "urls.original", String.class);
				String imageFilename = url.substring(url.lastIndexOf('/') + 1);
				String imageId = imageFilename.substring(0, imageFilename.lastIndexOf('.'));
				
				CompletableFuture<?> asyncResponse = downloadImage(url,
				                                                   headers,
				                                                   httpClient,
				                                                   maxConcurrentStreams,
				                                                   postId,
				                                                   imageId,
				                                                   publishedDatetime,
				                                                   postTitle,
				                                                   imageNumber,
				                                                   imageFilename);
				
				imagesDownload.add(asyncResponse);
				imageNumber++;
			}
		}
		
		CompletableFuture.allOf(imagesDownload.toArray(CompletableFuture[]::new)).join();
		
		saveCurrentMostRecentPost(currentMostRecentPost);
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
