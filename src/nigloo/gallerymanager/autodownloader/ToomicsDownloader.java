package nigloo.gallerymanager.autodownloader;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ToomicsDownloader extends Downloader
{
	private static final DateTimeFormatter DATE_TIME_FORMATTER = toomicsDateTimeFormater();
	private static DateTimeFormatter toomicsDateTimeFormater()
	{
		// Exemple: Nov 27, 2020
		return new DateTimeFormatterBuilder().parseStrict()
											 .appendText(ChronoField.MONTH_OF_YEAR, TextStyle.SHORT)
											 .appendLiteral(' ')
											 .appendValue(ChronoField.DAY_OF_MONTH, 2)
											 .appendLiteral(", ")
											 .appendValue(ChronoField.YEAR, 4)
											 .toFormatter(Locale.US);
	}
	private static final Pattern POST_ONCLICK_PATTERN = Pattern.compile(".*(?:location.href=|'login', )'([^']*)'.*");
	private static final Pattern POST_URL_PATTERN = Pattern.compile("/en/webtoon/detail/code/(\\d+)/ep/(\\d+)/toon/(\\d+)");

	private record ToomicsPostExtraInfo(String postUrl, int postNum) {}
	
	private static final String HOST_KEY = "host";
	private static final String HEADERS_KEY = "headers";
	private static final String AVNO_KEY = "avno";

	@Override
	public DownloaderType getType()
	{
		return DownloaderType.TOOMICS;
	}

	@Override
	protected void onStartDownload(DownloadSession session) throws Exception
	{
		creatorId = "5238";
//		String host = "https://" + creatorId + ".com";
//
//		HttpRequest request = HttpRequest.newBuilder()
//		                                 .uri(new URI(host))
//		                                 .GET()
//		                                 .headers(getHeaders(host, null))
//		                                 .build();
//		HttpResponse<String> response = session.send(request, BodyHandlers.ofString());
//
//		String phpsessidCookie = parseCookies(response.headers().firstValue("Set-Cookie").get()).get("PHPSESSID")
//		                                                                                        .toString();
//		String avno = Jsoup.parseBodyFragment(response.body())
//		                   .selectFirst(".av-masonry-pagination.av-masonry-load-more")
//		                   .attr("data-avno");
//
//		session.setExtaInfo(HOST_KEY, host);
		session.setExtaInfo(HEADERS_KEY, getHeaders(session));
//		session.setExtaInfo(AVNO_KEY, avno);
	}
	
	@Override
	protected Iterator<Post> listPosts(DownloadSession session) throws Exception
	{
		return new ToomicsPostIterator(session);
	}

	private class ToomicsPostIterator extends BasePostIterator
	{
		private final Iterator<Post> postsIt;

		public ToomicsPostIterator(DownloadSession session) throws Exception
		{
			super(session);

			HttpRequest request = HttpRequest.newBuilder()
											 .uri(new URI("https://www.toomics.uk/en/webtoon/episode/toon/" + creatorId))
											 .GET()
											 .headers(session.getExtraInfo(HEADERS_KEY))
											 .build();

			HttpResponse<String> response = session.send(request, BodyHandlers.ofString());
			postsIt = Jsoup.parseBodyFragment(response.body())
					.select(".list-ep .normal_ep a")
					.stream()
					.map(postElement -> {
						String onclick = postElement.attr("onclick");
						Matcher m = POST_ONCLICK_PATTERN.matcher(onclick);
						if (!m.matches()) {
							throw new RuntimeException("Error when searching for Post URL in: " + onclick);
						}
						String url = m.group(1);
						m = POST_URL_PATTERN.matcher(url);
						if (!m.matches()) {
							throw new RuntimeException("Error when searching for Post id in: " + url);
						}
						String id = m.group(1);
						int num = Integer.parseInt(m.group(2));
						String title = postElement.selectFirst(".cell-title .line-3").text() + " " +
								postElement.selectFirst(".cell-num").text();
						ZonedDateTime publishedDatetime = DATE_TIME_FORMATTER.parse(
								postElement.selectFirst(".cell-time").text(), LocalDate::from)
								.atStartOfDay(ZoneOffset.UTC);

						return Post.create(id, title, publishedDatetime, new ToomicsPostExtraInfo(url, num));
					})
					.sorted(Comparator
									.comparing(Post::publishedDatetime)
									.thenComparingInt(p -> ((ToomicsPostExtraInfo)p.extraInfo()).postNum())
									.reversed())
					.iterator();

			computeNextPost();
		}
		
		@Override
		protected Post findNextPost()
		{
			return postsIt.hasNext() ? postsIt.next() : null;
		}
	}

	@Override
	protected CompletableFuture<List<PostImage>> listImages(DownloadSession session, Post post) throws Exception
	{
		ToomicsPostExtraInfo extraInfo = (ToomicsPostExtraInfo) post.extraInfo();

		HttpRequest request = HttpRequest.newBuilder()
										 .uri(new URI("https://www.toomics.uk" + extraInfo.postUrl()))
										 .GET()
										 .headers(session.getExtraInfo(HEADERS_KEY))
										 .build();

		HttpResponse<String> response = session.send(request, BodyHandlers.ofString());
		System.out.println(response);
		System.out.println(response.body());

//System.exit(42);
		if(true)return CompletableFuture.failedFuture(new UnsupportedOperationException());
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
					return PostImage.create(imageId, filename + extension, url, null);
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
					return PostImage.create(imageId, filename + extension, url, null);
				}).toList();
			}
			
			return List.of();
		});
	}

	@Override
	protected String[] getHeadersForImageDownload(DownloadSession session, PostImage image)
	{
		return session.getExtraInfo(HEADERS_KEY);
	}
	
	private String[] getHeaders(DownloadSession session)
	{
		// @formatter:off
		return new String[] {
				"accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
				"accept-encoding", "gzip, deflate, br, zstd",
				"accept-language", "fr-FR,fr;q=0.9",
				"cache-control", "max-age=0",
				"cookie", session.getSecret("toomics.cookie"),
				"priority", "u=0, i",
				"sec-ch-ua", "\"Not A(Brand\";v=\"8\", \"Chromium\";v=\"132\", \"Brave\";v=\"132\"",
				"sec-ch-ua-mobile", "?0",
				"sec-ch-ua-platform", "\"Windows\"",
				"sec-fetch-dest", "document",
				"sec-fetch-mode", "navigate",
				"sec-fetch-site", "none",
				"sec-fetch-user", "?1",
				"sec-gpc", "1",
				"upgrade-insecure-requests", "1",
				"user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
		};
		// @formatter:on
	}
}
