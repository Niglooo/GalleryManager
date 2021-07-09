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
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import com.github.mizosoft.methanol.MoreBodyHandlers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import nigloo.tool.gson.JsonHelper;

public class FanboxDownloader extends BaseDownloader
{
	@SuppressWarnings("unused")
	private FanboxDownloader()
	{
		super();
	}
	
	public FanboxDownloader(String creatorId)
	{
		super(creatorId);
	}
	
	@Override
	public void download(Properties config) throws Exception
	{
		String cookie = config.getProperty("cookie.fanbox");
		
		System.out.println(creatorId);
		System.out.println(imagePathPattern);
		
		final Executor executor = Executors.newWorkStealingPool();
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
		
		String currentUrl = "https://api.fanbox.cc/post.listCreator?creatorId=" + creatorId + "&limit=10";
		
		while (currentUrl != null)
		{
			request = HttpRequest.newBuilder().uri(new URI(currentUrl)).GET().headers(headers).build();
			maxConcurrentStreams.acquire();
			response = httpClient.send(request, MoreBodyHandlers.decoding(BodyHandlers.ofString()));
			print(response, PrintOption.REQUEST_URL, PrintOption.STATUS_CODE);
			maxConcurrentStreams.release();
			
			parsedResponse = JsonParser.parseString(response.body().toString()).getAsJsonObject();
			for (JsonElement item : JsonHelper.followPath(parsedResponse, "body.items", JsonArray.class))
			{
				JsonArray images = JsonHelper.followPath(item, "body.images", JsonArray.class);
				if (images == null || images.size() == 0)
					continue;
				
				String postId = JsonHelper.followPath(item, "id", String.class);
				String postTitle = JsonHelper.followPath(item, "title", String.class);
				ZonedDateTime publishedDatetime = ZonedDateTime.parse(item.getAsJsonObject()
				                                                          .get("publishedDatetime")
				                                                          .getAsString());
				
				int imageNumber = 1;
				for (JsonElement image : images)
				{
					String imageId = JsonHelper.followPath(image, "id", String.class);
					String url = JsonHelper.followPath(image, "originalUrl", String.class);
					
					String imageFilename = url.substring(url.lastIndexOf('/') + 1);
					
					CompletableFuture<?> asyncResponse = downloadImage(url, cookie, httpClient, maxConcurrentStreams, postId, imageId, publishedDatetime, postTitle, imageNumber, imageFilename);
					
					imagesDownload.add(asyncResponse);
					imageNumber++;
				}
			}
			
			currentUrl = JsonHelper.followPath(parsedResponse, "body.nextUrl", String.class);
		}
		
		CompletableFuture.allOf(imagesDownload.toArray(CompletableFuture[]::new)).join();
	}
	
	@Override
	protected String[] getHeaders(String cookie)
	{
		// @formatter:off
		return new String[] {
			"accept", "application/json, text/plain, */*",
			"accept-encoding", "gzip, deflate, br",
			"accept-language", "fr-FR,fr;q=0.9",
			"cookie", cookie,
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
