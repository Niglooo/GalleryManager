package nigloo.gallerymanager.autodownloader;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.mizosoft.methanol.MoreBodyHandlers;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import nigloo.gallerymanager.model.Gallery;
import nigloo.gallerymanager.model.Image;
import nigloo.tool.Utils;
import nigloo.tool.gson.JsonHelper;
import nigloo.tool.injection.Injector;
import nigloo.tool.injection.annotation.Inject;

public class FanboxDownloader
{
	@Inject
	private transient Gallery gallery;
	
	private final String creatorId;
	private String imagePathPattern;
	
	@JsonAdapter(value = MappingTypeAdapter.class, nullSafe = false)
	private Map<FanboxImageKey, ImageReference> mapping = new LinkedHashMap<>();
	
	@SuppressWarnings("unused")
	private FanboxDownloader()
	{
		this(null);
	}
	
	public FanboxDownloader(String creatorId)
	{
		this.creatorId = creatorId;
		Injector.init(this);
	}
	
	public void download(String cookie) throws Exception
	{
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
		
		String[] headers = getFanboxHeaders(cookie);
		
//		for (int i = 0 ; i + 1 < headers.length ; i += 2)
//			System.out.println(headers[i]+": "+headers[i+1]);
		
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
				String publishedDatetimeISO = DateTimeFormatter.ISO_LOCAL_DATE.format(publishedDatetime);
				
				boolean adaptPost = mapping.containsKey(new FanboxImageKey(postId, "NA"));
				if (adaptPost)
					mapping.remove(new FanboxImageKey(postId, "NA"));
				
				int imgNum = 1;
				for (JsonElement image : images)
				{
					String imageId = JsonHelper.followPath(image, "id", String.class);
					String url = JsonHelper.followPath(image, "originalUrl", String.class);
					
					String imageNumber = String.format("%02d", imgNum++);
					String imageFilename = url.substring(url.lastIndexOf('/') + 1);
					
					FanboxImageKey imageKey = new FanboxImageKey(postId, imageId);
					ImageReference imageReference;
					Path imageDest;
					
					if (adaptPost)
						mapping.put(imageKey, null);
					
					if (mapping.containsKey(imageKey))
					{
						imageReference = mapping.get(imageKey);
						if (imageReference == null)
							continue;
						else
						{
							imageDest = imageReference.getImage().getPath();
							
							if (creatorId.equals("bubukka___"))
							{
								Path newDest = Paths.get("bubukka\\{postDatetime} {postTitle}\\{imageNumber}{imageFilename}".replace("{creatorId}",
								                                                                                                     creatorId)
								                                                                                            .replace("{postId}",
								                                                                                                     postId)
								                                                                                            .replace("{postDatetime}",
								                                                                                                     publishedDatetimeISO)
								                                                                                            .replace("{postTitle}",
								                                                                                                     postTitle)
								                                                                                            .replace("{imageNumber}",
								                                                                                                     imageNumber)
								                                                                                            .replace("{imageFilename}",
								                                                                                                     imageFilename));
								Field pathField = Image.class.getDeclaredField("path");
								pathField.setAccessible(true);
								pathField.set(imageReference.getImage(), newDest);
								
								imageDest = gallery.toAbsolutePath(imageDest);
								newDest = gallery.toAbsolutePath(newDest);
								
								Files.createDirectories(newDest.getParent());
								Files.move(imageDest, newDest);
								
								if (Files.list(imageDest.getParent()).findAny().isEmpty())
									Files.deleteIfExists(imageDest.getParent());
								
								continue;
							}
						}
					}
					else
					{
						imageDest = Paths.get(imagePathPattern.replace("{creatorId}", creatorId)
						                                      .replace("{postId}", postId)
						                                      .replace("{postDatetime}", publishedDatetimeISO)
						                                      .replace("{postTitle}", postTitle)
						                                      .replace("{imageNumber}", imageNumber)
						                                      .replace("{imageFilename}", imageFilename));
						
						imageReference = null;
					}
					
					imageDest = gallery.toAbsolutePath(imageDest);
					
					if (Files.exists(imageDest))
						continue;
					
					Files.createDirectories(imageDest.getParent());
					
					request = HttpRequest.newBuilder().uri(new URI(url)).GET().headers(headers).build();
					maxConcurrentStreams.acquire();
					// TODO if imageReference==null then add image and mapping to gallery after
					// download?
					CompletableFuture<?> asyncResponse = httpClient.sendAsync(request, BodyHandlers.ofFile(imageDest))
					                                               .thenApply(saveInGallery(postId, imageId))
					                                               .thenApply(r -> print(r,
					                                                                      PrintOption.REQUEST_URL,
					                                                                      PrintOption.STATUS_CODE,
					                                                                      PrintOption.RESPONSE_BODY))
					                                               .thenRun(maxConcurrentStreams::release);
					
					imagesDownload.add(asyncResponse);
				}
			}
			
			currentUrl = JsonHelper.followPath(parsedResponse, "body.nextUrl", String.class);
		}
		
		CompletableFuture.allOf(imagesDownload.toArray(CompletableFuture[]::new)).join();
		
//		request = HttpRequest.newBuilder()
//				.uri(new URI("https://oauth.secure.pixiv.net/auth/token"))
//				.POST(new FormUrlEncodedBodyPublisher()
//						.put("client_id", "KzEZED7aC0vird8jWyHM38mXjNTY")
//						.put("client_secret", "W9JZoJe00qPvJsiyCGT3CCtC6ZUtdpKpzMbNlUGP")
//						.put("grant_type", "password")
//						.put("username", "nigloo")
//						.put("password", "XXXXX")
//						)
//				.header("content-type", "application/x-www-form-urlencoded")
//				.build();
//		response = httpClient.send(request, BodyHandlers.ofString());
//		print(response);
	}
	
	private String[] getFanboxHeaders(String cookie)
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
	
	private Function<HttpResponse<Path>, HttpResponse<Path>> saveInGallery(String postId, String imageId)
	{
		return response ->
		{
			synchronized (this)
			{
				Path imagePath = gallery.toRelativePath(response.body());
				
				Image image = gallery.findImage(imagePath);
				if (image == null)
				{
					image = new Image(imagePath);
					gallery.saveImage(image);
				}
				ImageReference ref = new ImageReference(image);
				
				FanboxImageKey imagekey = new FanboxImageKey(postId, imageId);
				mapping.put(imagekey, ref);
				
				return response;
			}
		};
	}
	
	enum PrintOption
	{
		REQUEST_URL, STATUS_CODE, RESPONSE_HEADERS, RESPONSE_BODY
	}
	
	private static synchronized <T> HttpResponse<T> print(HttpResponse<T> response, PrintOption... options)
	{
		List<PrintOption> optionsLst = (options != null && options.length > 0) ? Arrays.asList(options)
		        : List.of(PrintOption.REQUEST_URL,
		                  PrintOption.STATUS_CODE,
		                  PrintOption.RESPONSE_HEADERS,
		                  PrintOption.RESPONSE_BODY);
		boolean error = response.statusCode() >= 300;
		
		if (optionsLst.contains(PrintOption.REQUEST_URL) || error)
			System.out.println("URL: " + response.request().uri());
		
		if (optionsLst.contains(PrintOption.STATUS_CODE) || error)
			System.out.println("Status: " + response.statusCode());
		
		if (optionsLst.contains(PrintOption.RESPONSE_HEADERS) || error)
		{
			System.out.println("Headers:");
			System.out.println(response.headers()
			                           .map()
			                           .entrySet()
			                           .stream()
			                           .map(e -> "    " + e.getKey() + ": " + e.getValue())
			                           .collect(Collectors.joining("\n")));
		}
		
		if (optionsLst.contains(PrintOption.RESPONSE_BODY) || error)
		{
			System.out.println("Body:");
			System.out.println(prettyToString(response.body()));
		}
		
		return response;
	}
	
	private static String prettyToString(Object obj)
	{
		if (obj == null)
			return null;
		
		String str = obj.toString();
		
		try
		{
			return new GsonBuilder().setPrettyPrinting()
			                        .disableHtmlEscaping()
			                        .create()
			                        .toJson(JsonParser.parseString(str));
		}
		catch (Exception e)
		{
			return str;
		}
	}
	
	@SuppressWarnings("unused")
	private static String[] parseHeader(String rawHeader)
	{
		return rawHeader.lines().filter(s -> !s.isBlank()).flatMap(s ->
		{
			int i = s.indexOf(':');
			return Stream.of(s.substring(0, i).trim(), s.substring(i + 1).trim());
		}).toArray(String[]::new);
	}
	
	@SuppressWarnings("unused")
	private static class FormUrlEncodedBodyPublisher implements BodyPublisher
	{
		private Map<String, String> parameters = new LinkedHashMap<>();
		private BodyPublisher delegate = null;
		
		public FormUrlEncodedBodyPublisher put(String key, String value)
		{
			if (delegate != null)
				throw new IllegalStateException("Cannot add parameter: subscribe or contentLength already called");
			
			parameters.put(key, URLEncoder.encode(value, StandardCharsets.UTF_8));
			return this;
		}
		
		private void initDelegate()
		{
			if (delegate != null)
				return;
			
			delegate = BodyPublishers.ofString(parameters.entrySet()
			                                             .stream()
			                                             .map(p -> p.getKey() + "=" + p.getValue())
			                                             .collect(Collectors.joining("&")));
		}
		
		@Override
		public void subscribe(Subscriber<? super ByteBuffer> subscriber)
		{
			initDelegate();
			delegate.subscribe(subscriber);
		}
		
		@Override
		public long contentLength()
		{
			initDelegate();
			return delegate.contentLength();
		}
	}
	
	public record FanboxImageKey(String postId, String imageId) implements Comparable<FanboxImageKey>
	{
		public FanboxImageKey
		{
			Objects.requireNonNull(postId, "postId");
			// Objects.requireNonNull(imageId, "imageId");
		}
		
		@Override
		public int hashCode()
		{
			return postId.hashCode() ^ imageId.hashCode();
		}
		
		@Override
		public boolean equals(Object obj)
		{
			return !(obj instanceof FanboxImageKey) ? false
			        : postId.equals(((FanboxImageKey) obj).postId) && imageId.equals(((FanboxImageKey) obj).imageId);
		}
		
		@Override
		public int compareTo(FanboxImageKey o)
		{
			int res = Utils.NATURAL_ORDER.compare(postId, o.postId);
			return (res != 0) ? res : Utils.NATURAL_ORDER.compare(imageId, o.imageId);
		}
	}

	static class MappingTypeAdapter extends TypeAdapter<Map<FanboxImageKey, ImageReference>>
	{
		@Override
		public void write(JsonWriter out, Map<FanboxImageKey, ImageReference> value) throws IOException
		{
			if (value == null)
				value = Collections.emptyMap();
			
			boolean serializeNulls = out.getSerializeNulls();
			
			out.beginArray();
			
			for (Entry<FanboxImageKey, ImageReference> entry : value.entrySet()
			                                                        .stream()
			                                                        .sorted(Comparator.comparing((Entry<FanboxImageKey, ImageReference> e) -> e.getKey())
			                                                                          .reversed())
			                                                        .toList())
			{
				FanboxImageKey imageKey = entry.getKey();
				ImageReference ref = entry.getValue();
				
				out.beginObject();
				out.name("postId");
				out.value(imageKey.postId);
				out.name("imageId");
				out.value(imageKey.imageId);
				out.name("imageRef");
				out.setSerializeNulls(true);
				if (ref == null)
					out.nullValue();
				else
					out.value(ref.getImageId());
				out.setSerializeNulls(serializeNulls);
				out.endObject();
			}
			
			out.endArray();
		}
		
		@Override
		public Map<FanboxImageKey, ImageReference> read(JsonReader in) throws IOException
		{
			Map<FanboxImageKey, ImageReference> mapping = new LinkedHashMap<>();
			
			if (in.peek() == JsonToken.NULL)
			{
				in.nextNull();
				return mapping;
			}
			
			in.beginArray();
			
			while (in.peek() != JsonToken.END_ARRAY)
			{
				if (in.peek() == JsonToken.NULL)
				{
					in.nextNull();
					continue;
				}
				
				String postId = null;
				String imageId = null;
				ImageReference ref = null;
				
				in.beginObject();
				
				while (in.peek() != JsonToken.END_OBJECT)
				{
					String property = in.nextName();
					if (in.peek() == JsonToken.NULL)
					{
						in.nextNull();
						continue;
					}
					
					switch (property)
					{
						case "postId":
							postId = in.nextString();
							break;
						case "imageId":
							imageId = in.nextString();
							break;
						case "imageRef":
							ref = new ImageReference(in.nextLong());
							break;
						default:
							in.skipValue();
							break;
					}
				}
				
				in.endObject();
				if (imageId == null)
					imageId = "NA";
				mapping.put(new FanboxImageKey(postId, imageId), ref);
			}
			
			in.endArray();
			
			return mapping;
		}
	}
	
	public boolean isHandling(Image image)
	{
		for (ImageReference ref : mapping.values())
			if (ref != null && ref.getImage().equals(image))
				return true;
			
		return false;
	}
}
