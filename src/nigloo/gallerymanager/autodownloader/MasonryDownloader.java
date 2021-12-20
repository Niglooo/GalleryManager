package nigloo.gallerymanager.autodownloader;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.github.mizosoft.methanol.MoreBodyHandlers;

import nigloo.gallerymanager.AsyncPools;
import nigloo.tool.StrongReference;

public class MasonryDownloader extends BaseDownloader
{
	private static final Logger LOGGER = LogManager.getLogger(MasonryDownloader.class);
	
	@Override
	public void download(Properties secrets, boolean checkAllPost) throws Exception
	{
		String host = "http://" + creatorId + ".com";
		
		LOGGER.debug(creatorId);
		LOGGER.debug(imagePathPattern);
		
		final StrongReference<ZonedDateTime> currentMostRecentPost = initCurrentMostRecentPost();
		final Semaphore maxConcurrentStreams = new Semaphore(10);// TODO init with max_concurrent_streams from http2
		final Collection<CompletableFuture<?>> downloads = new ArrayList<>();
		
		final HttpClient httpClient = HttpClient.newBuilder()
		                                        .followRedirects(Redirect.NORMAL)
		                                        .executor(AsyncPools.HTTP_REQUEST)
		                                        .build();
		HttpRequest request;
		HttpResponse<?> response;
		
		request = HttpRequest.newBuilder()
		                     .uri(new URI(host))
		                     .GET()
		                     .headers(getHeaders(host, null))
		                     .build();
		maxConcurrentStreams.acquire();
		response = httpClient.send(request, MoreBodyHandlers.decoding(BodyHandlers.ofString()));
		print(response, PrintOption.REQUEST_URL, PrintOption.STATUS_CODE);
		maxConcurrentStreams.release();
		
		String phpsessidCookie = parseCookies(response.headers().firstValue("Set-Cookie").get()).get("PHPSESSID").toString();
		String avno = Jsoup.parseBodyFragment(response.body().toString()).selectFirst(".av-masonry-pagination.av-masonry-load-more").attr("data-avno");
		
		
		
		String[] headers = getHeaders(host, phpsessidCookie);
		final String getPostListUrl = host + "/wp/wp-admin/admin-ajax.php";
		final String postListBaseParam = "avno="+avno+"&categories=1&taxonomy=category&orientation=&custom_bg=&color=custom&query_order=DESC&query_orderby=date&custom_markup=&set_breadcrumb=1&auto_ratio=1.7&columns=5&sort=yes&prod_order=&prod_order_by=&wc_prod_visible=&caption_styling=overlay&caption_display=on-hover&caption_elements=title&paginate=load_more&container_class=&container_links=1&overlay_fx=active&gap=1px&size=fixed+masonry&items=18&post_type=post%2C+page%2C+attachment%2C+revision%2C+nav_menu_item%2C+custom_css%2C+customize_changeset%2C+oembed_cache%2C+user_request%2C+wp_block%2C+feedback%2C+tt_font_control%2C+jp_mem_plan%2C+jp_pay_order%2C+jp_pay_product%2C+portfolio%2C+avia_framework_post&link=category%2C+1&action=avia_ajax_masonry_more&ids=";
		
		List<String> loaded = new ArrayList<>();
		
		mainloop:
		while (true)
		{
			String postParam = postListBaseParam + "&offset=" + loaded.size()
			        + loaded.stream().map(id -> "&loaded%5B%5D=" + id).collect(Collectors.joining());
			request = HttpRequest.newBuilder()
			                     .uri(new URI(getPostListUrl))
			                     .POST(BodyPublishers.ofString(postParam))
			                     .headers(headers)
			                     .build();
			maxConcurrentStreams.acquire();
			response = httpClient.send(request, MoreBodyHandlers.decoding(BodyHandlers.ofString()));
			print(response, PrintOption.REQUEST_URL, PrintOption.STATUS_CODE);
			maxConcurrentStreams.release();
			
			Elements postLinkElements = Jsoup.parseBodyFragment(response.body().toString()).select("body > a");
			if (postLinkElements.isEmpty())
				break;
			
			for (Element postLinkElement : postLinkElements)
			{
				String postUrl = postLinkElement.attr("href");
				String postId = postLinkElement.attr("data-av-masonry-item");
				String postTitle = postLinkElement.attr("title");
				// tags: all postLinkElement.attributes() starting with tag-
				ZonedDateTime publishedDatetime = LocalDate.parse(postLinkElement.selectFirst(".av-masonry-date")
				                                                                 .text())
				                                           .atTime(0, 0, 0)
				                                           .atZone(ZoneOffset.UTC);
				
				updateCurrentMostRecentPost(currentMostRecentPost, publishedDatetime);
				if (dontCheckPost(publishedDatetime, checkAllPost))
					break mainloop;
				
				loaded.add(postId);
				
				request = HttpRequest.newBuilder().uri(new URI(postUrl)).GET().headers(headers).build();
				maxConcurrentStreams.acquire();
				downloads.add(httpClient.sendAsync(request, MoreBodyHandlers.decoding(BodyHandlers.ofString()))
				                        .thenApply(print(PrintOption.REQUEST_URL, PrintOption.STATUS_CODE))
				                        .thenApply(release(maxConcurrentStreams))
				                        .thenCompose(downloadImages(httpClient,
				                                                    headers,
				                                                    maxConcurrentStreams,
				                                                    postId,
				                                                    postTitle,
				                                                    publishedDatetime)));
			}
		}
		
		CompletableFuture.allOf(downloads.toArray(CompletableFuture[]::new)).join();
		
		saveCurrentMostRecentPost(currentMostRecentPost);
	}
	
	private Function<HttpResponse<String>, CompletableFuture<?>> downloadImages(HttpClient httpClient,
	                                                                            String[] headers,
	                                                                            Semaphore maxConcurrentStreams,
	                                                                            String postId,
	                                                                            String postTitle,
	                                                                            ZonedDateTime publishedDatetime)
	{
		return response ->
		{
			Collection<CompletableFuture<?>> downloads = new ArrayList<>();
			Document parsedResponse = Jsoup.parseBodyFragment(response.body().toString());
			Elements imagesElements = parsedResponse.select(".av-masonry-container > a");
			int imageNumber = 1;
			for (Element imageElement : imagesElements)
			{
				String imageId = imageElement.attr("data-av-masonry-item");
				String url = imageElement.attr("href");
				
				String imageFilename = url.substring(url.lastIndexOf('/') + 1);
				
				downloads.add(downloadImage(httpClient,
				                            url,
				                            headers,
				                            maxConcurrentStreams,
				                            postId,
				                            imageId,
				                            publishedDatetime,
				                            postTitle,
				                            imageNumber,
				                            imageFilename));
				
				imageNumber++;
			}
			
			return CompletableFuture.allOf(downloads.toArray(CompletableFuture[]::new));
		};
	}
	
	private String[] getHeaders(String host, String cookie)
	{
		// @formatter:off
		String[] headers = {
				"Accept", "*/*",
				"Accept-Encoding", "gzip, deflate",
				"Accept-Language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7,ja;q=0.6",
				"Content-Type", "application/x-www-form-urlencoded; charset=UTF-8",
				"Origin", host,
				"Referer", host+"/illustrations/",
				"User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.93 Safari/537.36",
				"X-Requested-With", "XMLHttpRequest",
				"Cookie", cookie};
		// @formatter:on
		
		if (cookie == null) 
			headers = Arrays.copyOf(headers, headers.length-2);
		
		return headers;
	}
}
