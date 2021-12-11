package nigloo.gallerymanager.autodownloader;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.github.mizosoft.methanol.MoreBodyHandlers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import nigloo.tool.StrongReference;
import nigloo.tool.gson.JsonHelper;

public class FanboxDownloader extends BaseDownloader
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
	public void download(Properties secrets, boolean checkAllPost) throws Exception
	{
		String cookie = secrets.getProperty("fanbox.cookie");
		
		System.out.println(creatorId);
		System.out.println(imagePathPattern);
		
		final StrongReference<ZonedDateTime> currentMostRecentPost = initCurrentMostRecentPost();
		
		final Executor executor = Executors.newWorkStealingPool();
		final Semaphore maxConcurrentStreams = new Semaphore(10);// TODO init with max_concurrent_streams from http2
		final Collection<CompletableFuture<?>> downloads = Collections.synchronizedCollection(new ArrayList<>());
		
		final HttpClient httpClient = HttpClient.newBuilder()
		                                        .followRedirects(Redirect.NORMAL)
		                                        .executor(executor)
		                                        .build();
		HttpRequest request;
		HttpResponse<?> response;
		JsonObject parsedResponse;
		
		String[] headers = getHeaders(cookie);
		
		String currentUrl = "https://api.fanbox.cc/post.listCreator?creatorId=" + creatorId + "&limit=10";
		
		mainloop:
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
				if (images == null)
				{
					JsonObject imageMap = JsonHelper.followPath(item, "body.imageMap", JsonObject.class);
					if (imageMap != null)
					{
						images = new JsonArray(imageMap.size());
						JsonArray blocks = JsonHelper.followPath(item, "body.blocks", JsonArray.class);
						for (JsonElement block : blocks)
						{
							String type = JsonHelper.followPath(block, "type", String.class);
							if ("image".equals(type))
							{
								String imageId = JsonHelper.followPath(block, "imageId", String.class);
								images.add(imageMap.get(imageId));
							}
							
						}
					}
					else
						images = new JsonArray(0);
				}
				
				JsonArray files = downloadFiles ? JsonHelper.followPath(item, "body.files", JsonArray.class) : null;
				if (files == null)
					files = new JsonArray(0);
				
				if (images.size() == 0 && files.size() == 0)
					continue;
				
				String postId = JsonHelper.followPath(item, "id", String.class);
				String postTitle = JsonHelper.followPath(item, "title", String.class);
				ZonedDateTime publishedDatetime = ZonedDateTime.parse(JsonHelper.followPath(item,
				                                                                            "publishedDatetime",
				                                                                            String.class));
				
				updateCurrentMostRecentPost(currentMostRecentPost, publishedDatetime);
				if (dontCheckPost(publishedDatetime, checkAllPost))
					break mainloop;
				
				if (images.size() > 0)
				{
					int imageNumber = 1;
					for (JsonElement image : images)
					{
						String imageId = JsonHelper.followPath(image, "id", String.class);
						String url = JsonHelper.followPath(image, "originalUrl", String.class);
						
						String imageFilename = url.substring(url.lastIndexOf('/') + 1);
						
						downloads.add(downloadImage(url,
						                            headers,
						                            httpClient,
						                            maxConcurrentStreams,
						                            postId,
						                            imageId,
						                            publishedDatetime,
						                            postTitle,
						                            imageNumber,
						                            imageFilename));
						imageNumber++;
					}
				}
				
				if (files.size() > 0)
				{
					for (JsonElement file : files)
					{
						String fileId = JsonHelper.followPath(file, "id", String.class);
						String url = JsonHelper.followPath(file, "url", String.class);
						String fileNameWithoutExtention = JsonHelper.followPath(file, "name", String.class);
						String fileExtention = JsonHelper.followPath(file, "extension", String.class);
						
						downloads.add(downloadFile(url,
						                           headers,
						                           httpClient,
						                           maxConcurrentStreams,
						                           postId,
						                           fileId,
						                           publishedDatetime,
						                           postTitle,
						                           fileNameWithoutExtention,
						                           fileExtention));
					}
				}
			}
			
			currentUrl = JsonHelper.followPath(parsedResponse, "body.nextUrl", String.class);
		}
		
		CompletableFuture.allOf(downloads.toArray(CompletableFuture[]::new)).join();
		
		saveCurrentMostRecentPost(currentMostRecentPost);
	}
	
	private CompletableFuture<Void> downloadFile(String url,
	                                             String[] headers,
	                                             HttpClient httpClient,
	                                             Semaphore maxConcurrentStreams,
	                                             String postId,
	                                             String fileId,
	                                             ZonedDateTime publishedDatetime,
	                                             String postTitle,
	                                             String fileNameWithoutExtention,
	                                             String fileExtention)
	        throws Exception
	{
		String fileName = fileNameWithoutExtention + '.' + fileExtention;
		if ("zip".equals(fileExtention) && autoExtractZip)
			fileName = fileNameWithoutExtention;
		
		Path fileDest = Paths.get(imagePathPattern.replace("{creatorId}", creatorId.trim())
		                                          .replace("{postId}", postId.trim())
		                                          .replace("{postDate}",
		                                                   DateTimeFormatter.ISO_LOCAL_DATE.format(publishedDatetime))
		                                          .replace("{postTitle}", postTitle.trim())
		                                          .replace("{imageNumber} ", "")
		                                          .replace(" {imageNumber}", "")
		                                          .replace("{imageNumber}", "")
		                                          .replace("{imageFilename}", fileName.trim()));
		fileDest = makeSafe(gallery.toAbsolutePath(fileDest));
		
		if (Files.exists(fileDest))
			return CompletableFuture.completedFuture(null);
		
		Files.createDirectories(fileDest.getParent());
		
		HttpRequest request = HttpRequest.newBuilder().uri(new URI(url)).GET().headers(headers).build();
		maxConcurrentStreams.acquire();
		return httpClient.sendAsync(request, BodyHandlers.ofFile(fileDest))
		                 .thenApply(unZip("zip".equals(fileExtention)))
		                 .thenApply(r -> print(r,
		                                       PrintOption.REQUEST_URL,
		                                       PrintOption.STATUS_CODE,
		                                       PrintOption.RESPONSE_BODY))
		                 .thenRun(maxConcurrentStreams::release);
	}
	
	private Function<HttpResponse<Path>, HttpResponse<Path>> unZip(boolean isZip)
	{
		return response ->
		{
			if (!isZip || !autoExtractZip)
				return response;
			
			
			
			Path filePath = response.body();
			Path zipFilePath = filePath.resolveSibling(filePath.getFileName()+".zip");
			
			System.out.println("Unziping: " + zipFilePath);
			
			try
			{
				//FIXME, sometime filePath is still opened by the downloader
				Files.move(filePath, zipFilePath);
				Files.createDirectory(filePath);
			}
			catch (IOException e)
			{
				e.printStackTrace(System.err);
				return response;
			}
			
			try
			{
				ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipFilePath));
				ZipEntry zipEntry = zis.getNextEntry();
				while (zipEntry != null)
				{
					Path entryPath = filePath.resolve(zipEntry.getName());
					
					if (zipEntry.isDirectory())
					{
						Files.createDirectories(entryPath);
					}
					else
					{
						Files.createDirectories(entryPath.getParent());
						Files.copy(zis, entryPath);
						// if an image, gallery.saveImage(image);
					}
					zipEntry = zis.getNextEntry();
				}
				zis.closeEntry();
				zis.close();
				
				Files.delete(zipFilePath);
			}
			catch (IOException e)
			{
				e.printStackTrace(System.err);
				return response;
			}
			
			return response;
			
		};
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
