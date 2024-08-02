package nigloo.gallerymanager.autodownloader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import nigloo.tool.gson.JsonHelper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.TextStyle;
import java.time.temporal.ChronoField;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public class SubscribeStarDownloader extends Downloader
{
    private static final String HEADERS_KEY = "headers";

    private static final DateTimeFormatter DATE_TIME_FORMATTER = new DateTimeFormatterBuilder().parseStrict()
            .appendText(ChronoField.MONTH_OF_YEAR, TextStyle.SHORT_STANDALONE)
            .appendLiteral(' ')
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .appendLiteral(", ")
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral(' ')
            .appendValue(ChronoField.CLOCK_HOUR_OF_AMPM, 2)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .appendLiteral(' ')
            .parseCaseInsensitive()
            .appendDayPeriodText(TextStyle.SHORT)
            .toFormatter(Locale.US);

    @Override
    public DownloaderType getType()
    {
        return DownloaderType.SUBSCRIBESTAR;
    }

    private String buildUrl(String path) {
        return "https://subscribestar.adult" + path;
    }

    @Override
    protected void onStartDownload(DownloadSession session)
    {
        session.setExtaInfo(HEADERS_KEY, getHeaders(session));
    }

    @Override
    protected Iterator<Post> listPosts(DownloadSession session) throws Exception {
        return new SubscribeStarPostIterator(session);
    }

    private class SubscribeStarPostIterator extends BasePostIterator
    {
        private String getPostListUrl;
        private Iterator<Post> postsIt;

        public SubscribeStarPostIterator(DownloadSession session) throws Exception {
            super(session);
            getPostListUrl = buildUrl("/" + creatorId);
            postsIt = Collections.emptyIterator();
            computeNextPost();
        }

        @Override
        protected Post findNextPost() throws Exception {
            if (postsIt.hasNext())
                return postsIt.next();

            if (getPostListUrl == null)
                return null;

            HttpRequest request = HttpRequest.newBuilder()
                                             .uri(new URI(getPostListUrl))
                                             .GET()
                                             .headers(session.getExtraInfo(HEADERS_KEY))
                                             .build();
            HttpResponse<String> response = session.send(request, HttpResponse.BodyHandlers.ofString());

            Document postElements;
            if (response.headers().firstValue("Content-Type").get().contains("text/html"))
            {
                postElements = Jsoup.parseBodyFragment(response.body());
                if (postElements.selectFirst(".top_bar-user-menu_wrapper") == null)
                    throw new DownloaderSessionExpiredException();
            }
            else
            {
                JsonObject parsedResponse = JsonParser.parseString(response.body()).getAsJsonObject();
                postElements = Jsoup.parseBodyFragment(JsonHelper.followPath(parsedResponse, "html"));
            }

            Element nextLink = postElements.selectFirst(".posts-more[data-role=\"infinite_scroll-next_page\"]");
            getPostListUrl = nextLink != null ? buildUrl(nextLink.attr("href")) : null;
            
            postsIt = postElements
                    .select(".post")
                    .stream()
                    .filter(postElement -> postElement.selectFirst(".post-body.is-locked") == null)
                    .map(postElement -> {
                        String postId = postElement.attr("data-id");
                        String postTitle = postElement.selectFirst(".post-body").text();
                        ZonedDateTime publishedDatetime = DATE_TIME_FORMATTER.parse(postElement.selectFirst(".post-date").text(), LocalDateTime::from).atZone(ZoneOffset.UTC);

                        return Post.create(postId, postTitle, publishedDatetime, postElement);
                    })
                    .iterator();

            return findNextPost();
        }
    }

    @Override
    protected CompletableFuture<List<PostImage>> listImages(DownloadSession session, Post post) {

        Element imagesElement = ((Element) post.extraInfo()).selectFirst(".post-uploads .uploads-images");
        if (imagesElement == null)
            return CompletableFuture.completedFuture(List.of());

        String imagesRawData = imagesElement.attr("data-gallery");
        JsonArray imagesData = JsonParser.parseString(imagesRawData).getAsJsonArray();

        List<PostImage> images = imagesData
                .asList()
                .stream()
                .map(JsonElement::getAsJsonObject)
                .map(imageData -> {
                    String imageId = JsonHelper.followPath(imageData, "id");
                    String imageFilename = JsonHelper.followPath(imageData, "original_filename");
                    String url = JsonHelper.followPath(imageData, "url");
                    if (url != null && url.startsWith("/"))
                    {
                        url = buildUrl(url);
                    }

                    return PostImage.create(imageId, imageFilename, url, null);
                })
                .toList();

        return CompletableFuture.completedFuture(images);
    }

    //TODO implement listFiles

    @Override
    protected String[] getHeadersForImageDownload(DownloadSession session, PostImage image)
    {
        return session.getExtraInfo(HEADERS_KEY);
    }

    private String[] getHeaders(DownloadSession session)
    {
        // @formatter:off
        return new String[] {
            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Encoding", "gzip, deflate",
            "Accept-Language", "fr-FR,fr;q=0.7",
            "Cache-Control", "max-age=0",
            "Cookie", session.getSecret("subscribestar.cookie"),
            "Sec-Ch-Ua", "\"Brave\";v=\"123\", \"Not:A-Brand\";v=\"8\", \"Chromium\";v=\"123\"",
            "Sec-Ch-Ua-Mobile", "?0",
            "Sec-Ch-Ua-Platform",  "\"Windows\"",
            "Sec-Fetch-Dest",  "document",
            "Sec-Fetch-Mode", "navigate",
            "Sec-Fetch-Site", "none",
            "Sec-Fetch-User", "?1",
            "Sec-Gpc", "1",
            "Upgrade-Insecure-Requests", "1",
            "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
        };
        // @formatter:on
    }
}
