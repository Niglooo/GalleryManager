package nigloo.gallerymanager.autodownloader;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

public class MasonryDownloader extends Downloader
{
	private record MasonryPostExtraInfo(String postUrl, List<String> tags) {}
	
	private static final String HOST_KEY = "host";
	private static final String HEADERS_KEY = "headers";
	private static final String AVNO_KEY = "avno";
	
	@Override
	protected void onStartDownload(DownloadSession session) throws Exception
	{
		String host = "http://" + creatorId + ".com";
		
		HttpRequest request = HttpRequest.newBuilder()
		                                 .uri(new URI(host))
		                                 .GET()
		                                 .headers(getHeaders(host, null))
		                                 .build();
		HttpResponse<String> response = session.send(request, BodyHandlers.ofString());
		
		String phpsessidCookie = parseCookies(response.headers().firstValue("Set-Cookie").get()).get("PHPSESSID")
		                                                                                        .toString();
		String avno = Jsoup.parseBodyFragment(response.body())
		                   .selectFirst(".av-masonry-pagination.av-masonry-load-more")
		                   .attr("data-avno");
		
		session.setExtaInfo(HOST_KEY, host);
		session.setExtaInfo(HEADERS_KEY, getHeaders(host, phpsessidCookie));
		session.setExtaInfo(AVNO_KEY, avno);
	}
	
	@Override
	protected Iterator<Post> listPosts(DownloadSession session) throws Exception
	{
		return new MasonryPostIterator(session);
	}
	
	private class MasonryPostIterator extends BasePostIterator
	{
		private final String getPostListUrl;
		private final String postListBaseParam;
		
		private final List<String> loaded;
		private Iterator<Post> postsIt;
		
		public MasonryPostIterator(DownloadSession session) throws Exception
		{
			super(session);
			this.getPostListUrl = session.getExtraInfo(HOST_KEY) + "/wp/wp-admin/admin-ajax.php";
			this.postListBaseParam = "avno=" + session.getExtraInfo(AVNO_KEY)
			        + "&categories=1&taxonomy=category&orientation=&custom_bg=&color=custom&query_order=DESC&query_orderby=date&custom_markup=&set_breadcrumb=1&auto_ratio=1.7&columns=5&sort=yes&prod_order=&prod_order_by=&wc_prod_visible=&caption_styling=overlay&caption_display=on-hover&caption_elements=title&paginate=load_more&container_class=&container_links=1&overlay_fx=active&gap=1px&size=fixed+masonry&items=18&post_type=post%2C+page%2C+attachment%2C+revision%2C+nav_menu_item%2C+custom_css%2C+customize_changeset%2C+oembed_cache%2C+user_request%2C+wp_block%2C+feedback%2C+tt_font_control%2C+jp_mem_plan%2C+jp_pay_order%2C+jp_pay_product%2C+portfolio%2C+avia_framework_post&link=category%2C+1&action=avia_ajax_masonry_more&ids=";
			
			this.loaded = new ArrayList<>();
			this.postsIt = Collections.emptyIterator();
			computeNextPost();
		}
		
		@Override
		protected Post findNextPost() throws Exception
		{
			if (postsIt.hasNext())
				return postsIt.next();
			
			String postParam = postListBaseParam + "&offset=" + loaded.size()
			        + loaded.stream().map(id -> "&loaded%5B%5D=" + id).collect(Collectors.joining());
			HttpRequest request = HttpRequest.newBuilder()
			                                 .uri(new URI(getPostListUrl))
			                                 .POST(BodyPublishers.ofString(postParam))
			                                 .headers(session.getExtraInfo(HEADERS_KEY))
			                                 .build();
			HttpResponse<String> response = session.send(request, BodyHandlers.ofString());
			
			postsIt = Jsoup.parseBodyFragment(response.body()).select("body > a").stream().map(postLinkElement ->
			{
				String postUrl = postLinkElement.attr("href");
				String postId = postLinkElement.attr("data-av-masonry-item");
				String postTitle = postLinkElement.attr("title");
				List<String> tags = postLinkElement.classNames()
				                                   .stream()
				                                   .filter(a -> a.startsWith("tag-"))
				                                   .map(a -> a.substring("tag-".length()))
				                                   .toList();
				
				ZonedDateTime publishedDatetime = LocalDate.parse(postLinkElement.selectFirst(".av-masonry-date")
				                                                                 .text())
				                                           .atTime(0, 0, 0)
				                                           .atZone(ZoneOffset.UTC);
				loaded.add(postId);
				return Post.create(postId, postTitle, publishedDatetime, new MasonryPostExtraInfo(postUrl, tags));
			}).iterator();
			
			if (postsIt.hasNext())
				return postsIt.next();
			else
				return null;
		}
	}

	@Override
	protected CompletableFuture<List<PostImage>> listImages(DownloadSession session, Post post) throws Exception
	{
		MasonryPostExtraInfo extraInfo = (MasonryPostExtraInfo) post.extraInfo();
		
		HttpRequest request = HttpRequest.newBuilder()
		                                 .uri(new URI(extraInfo.postUrl()))
		                                 .GET()
		                                 .headers(session.getExtraInfo(HEADERS_KEY))
		                                 .build();
		
		return session.sendAsync(request, BodyHandlers.ofString()).thenApply(r ->
		{
			Document htmlPost = Jsoup.parseBodyFragment(r.body());
			
			// Regular post
			Elements imagesElement = htmlPost.select(".av-masonry-container > a");
			if (imagesElement.size() > 0)
			{
				return imagesElement.stream().map(imageElement ->
				{
					String imageId = imageElement.attr("data-av-masonry-item");
					String url = imageElement.attr("href");
					String filename = imageElement.selectFirst("img").attr("title");
					String extension = url.substring(url.lastIndexOf('.'));
					return PostImage.create(imageId, filename + extension, url, extraInfo.tags());
				}).toList();
			}
			
			// Single image post
			imagesElement = htmlPost.select(".avia-image-container img");
			if (imagesElement.size() > 0)
			{
				return imagesElement.stream().map(imageElement ->
				{
					String imageId = "single_image";
					String url = imageElement.attr("src");
					String filename = imageElement.attr("title");
					String extension = url.substring(url.lastIndexOf('.'));
					return PostImage.create(imageId, filename + extension, url, extraInfo.tags());
				}).toList();
			}
			
			return List.of();
		});
	}

	@Override
	protected String[] getHeardersForImageDownload(DownloadSession session, PostImage image)
	{
		return session.getExtraInfo(HEADERS_KEY);
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
			headers = Arrays.copyOf(headers, headers.length - 2);
		
		return headers;
	}
}
