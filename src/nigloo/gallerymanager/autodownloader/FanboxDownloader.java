package nigloo.gallerymanager.autodownloader;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.StreamSupport;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import nigloo.tool.gson.JsonHelper;

public class FanboxDownloader extends Downloader
{
	private boolean downloadFiles = true;
	private boolean autoExtractZip = true;
	
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
	protected void doDownload(DownloadSession session) throws Exception
	{
		String cookie = session.getSecret("fanbox.cookie");
		
		final Collection<CompletableFuture<?>> postsDownloads = new ArrayList<>();
		
		HttpRequest request;
		
		String[] headers = getHeaders(cookie);
		
		String currentUrl = "https://api.fanbox.cc/post.listCreator?creatorId=" + creatorId + "&limit=10";
		
		mainloop:
		while (currentUrl != null)
		{
			request = HttpRequest.newBuilder().uri(new URI(currentUrl)).GET().headers(headers).build();
			JsonElement response = session.send(request, JsonHelper.httpBodyHandler()).body();
			
			List<String> postIds = StreamSupport.stream(JsonHelper.followPath(response, "body.items", JsonArray.class)
			                                                      .spliterator(),
			                                            false)
			                                    .map(JsonElement::getAsJsonObject)
			                                    .map(post -> post.get("id").getAsString())
			                                    .toList();
			
			for (String postId : postIds)
			{
				request = HttpRequest.newBuilder()
				                     .uri(new URI("https://api.fanbox.cc/post.info?postId=" + postId))
				                     .GET()
				                     .headers(headers)
				                     .build();
				JsonElement post = session.send(request, JsonHelper.httpBodyHandler())
				                          .body()
				                          .getAsJsonObject()
				                          .get("body");
				
				JsonArray images = JsonHelper.followPath(post, "body.images", JsonArray.class);
				if (images == null)
				{
					JsonObject imageMap = JsonHelper.followPath(post, "body.imageMap", JsonObject.class);
					if (imageMap != null)
					{
						images = new JsonArray(imageMap.size());
						JsonArray blocks = JsonHelper.followPath(post, "body.blocks", JsonArray.class);
						for (JsonElement block : blocks)
						{
							String type = JsonHelper.followPath(block, "type");
							if ("image".equals(type))
							{
								String imageId = JsonHelper.followPath(block, "imageId");
								images.add(imageMap.get(imageId));
							}
						}
					}
					else
						images = new JsonArray(0);
				}
				
				JsonArray files = downloadFiles ? JsonHelper.followPath(post, "body.files", JsonArray.class) : null;
				if (files == null)
					files = new JsonArray(0);
				
				if (images.size() == 0 && files.size() == 0)
					continue;
				
				String postTitle = JsonHelper.followPath(post, "title");
				ZonedDateTime publishedDatetime = ZonedDateTime.parse(JsonHelper.followPath(post, "publishedDatetime"));
				
				if (session.stopCheckingPost(publishedDatetime))
					break mainloop;
				
				Collection<CompletableFuture<?>> imagesDownloads = new ArrayList<>();
				
				if (images.size() > 0)
				{
					int imageNumber = 1;
					for (JsonElement image : images)
					{
						String imageId = JsonHelper.followPath(image, "id");
						String url = JsonHelper.followPath(image, "originalUrl");
						
						String imageFilename = url.substring(url.lastIndexOf('/') + 1);
						
						imagesDownloads.add(downloadImage(session,
						                                  url,
						                                  headers,
						                                  postId,
						                                  imageId,
						                                  publishedDatetime,
						                                  postTitle,
						                                  imageNumber,
						                                  imageFilename,
						                                  null));
						imageNumber++;
					}
				}
				
				if (files.size() > 0)
				{
					for (JsonElement file : files)
					{
						String fileId = JsonHelper.followPath(file, "id");
						String url = JsonHelper.followPath(file, "url");
						String fileNameWithoutExtention = JsonHelper.followPath(file, "name");
						String fileExtention = JsonHelper.followPath(file, "extension");
						
						imagesDownloads.add(downloadFile(session,
						                                 url,
						                                 headers,
						                                 postId,
						                                 postTitle,
						                                 publishedDatetime,
						                                 fileId,
						                                 fileNameWithoutExtention,
						                                 fileExtention));
					}
				}
				
				postsDownloads.add(CompletableFuture.allOf(imagesDownloads.toArray(CompletableFuture[]::new))
				                                    .thenRun(() -> session.onPostDownloaded(publishedDatetime)));
			}
			
			currentUrl = JsonHelper.followPath(response, "body.nextUrl");
		}
		
		CompletableFuture.allOf(postsDownloads.toArray(CompletableFuture[]::new)).join();
		
		session.saveLastPublishedDatetime();
	}
	
	private CompletableFuture<?> downloadFile(DownloadSession session,
	                                          String url,
	                                          String[] headers,
	                                          String postId,
	                                          String postTitle,
	                                          ZonedDateTime publishedDatetime,
	                                          String fileId,
	                                          String fileNameWithoutExtention,
	                                          String fileExtention)
	{
		HttpRequest request = null;
		Path fileDest = null;
		try
		{
			String fileName = fileNameWithoutExtention + '.' + fileExtention;
			if ("zip".equals(fileExtention) && autoExtractZip)
				fileName = fileNameWithoutExtention;
			
			fileDest = Paths.get(imagePathPattern.replace("{creatorId}", creatorId.trim())
			                                     .replace("{postId}", postId.trim())
			                                     .replace("{postDate}",
			                                              DateTimeFormatter.ISO_LOCAL_DATE.format(publishedDatetime))
			                                     .replace("{postTitle}", postTitle.trim())
			                                     .replace("{imageNumber} ", "")
			                                     .replace("{imageNumber}", "")
			                                     .replace("{imageNumber}", "")
			                                     .replace("{imageFilename}", fileName.trim()));
			fileDest = makeSafe(gallery.toAbsolutePath(fileDest));
			
			if (Files.exists(fileDest))
				return CompletableFuture.completedFuture(null);
			
			Files.createDirectories(fileDest.getParent());
			
			request = HttpRequest.newBuilder().uri(new URI(url)).GET().headers(headers).build();
			return session.sendAsync(request, BodyHandlers.ofFile(fileDest))
			              .thenApply(unZip("zip".equals(fileExtention), autoExtractZip));
		}
		catch (Exception e)
		{
			return CompletableFuture.failedFuture(e);
		}
	}
	
	private String[] getHeaders(String cookie)
	{
		// @formatter:off
		return new String[] {
			"accept", "application/json, text/plain, */*",
			"accept-encoding", "gzip, deflate",
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
