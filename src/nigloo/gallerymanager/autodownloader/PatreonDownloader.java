package nigloo.gallerymanager.autodownloader;

import java.io.Reader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import nigloo.tool.gson.JsonHelper;

public class PatreonDownloader extends Downloader
{
	@SuppressWarnings("unused")
	private PatreonDownloader()
	{
		super();
	}
	
	public PatreonDownloader(String creatorId)
	{
		super(creatorId);
	}
	
	private static final String HEADERS_KEY = "headers";
	
	private record RessourcesId(String id, String type) {}
	private record PostExtraInfo(JsonElement jPost, Map<RessourcesId, JsonElement> resources) {}
	
	@Override
	protected void onStartDownload(DownloadSession session) throws Exception
	{
		session.setExtaInfo(HEADERS_KEY, getHeaders(session));
	}
	
	@Override
	protected Iterator<Post> listPosts(DownloadSession session) throws Exception
	{
		return new PatreonPostIterator(session);
	}
	
	private class PatreonPostIterator extends BasePostIterator
	{
		private final String campaignId;
		
		private String nextPageUrl;
		private Iterator<JsonElement> postsIt;
		private Map<RessourcesId, JsonElement> currentResourcesIncluded;
		
		public PatreonPostIterator(DownloadSession session) throws Exception
		{
			super(session);
			
			HttpRequest request = HttpRequest.newBuilder()
			                    .uri(new URI("https://www.patreon.com/" + creatorId + "/posts"))
			                    .GET()
			                    .headers(getWebUiHeaders())
			                    .version(Version.HTTP_1_1)// Avoid cloudflare bullshit
			                    .build();
			String response = session.send(request, BodyHandlers.ofString()).body();
			
			final String JSON_BOOTSTRAP_PREFIX = "Object.assign(window.patreon.bootstrap,";
			Reader reader = new StringReader(response);
			reader.skip(response.indexOf(JSON_BOOTSTRAP_PREFIX) + JSON_BOOTSTRAP_PREFIX.length());
			JsonReader jsonReader = new JsonReader(reader);
			jsonReader.setLenient(true);
			JsonElement bootstrap = JsonParser.parseReader(jsonReader);
			
			this.campaignId = JsonHelper.followPath(bootstrap, "creator.data.id");
			this.nextPageUrl = "https://www.patreon.com/api/posts" + 
					"?include=campaign%2Caccess_rules%2Cattachments%2Caudio%2Cimages%2Cmedia%2Cnative_video_insights%2Cpoll.choices%2Cpoll.current_user_responses.user%2Cpoll.current_user_responses.choice%2Cpoll.current_user_responses.poll%2Cuser%2Cuser_defined_tags%2Cti_checks" +
					"&fields[campaign]=currency%2Cshow_audio_post_download_links%2Cavatar_photo_url%2Cavatar_photo_image_urls%2Cearnings_visibility%2Cis_nsfw%2Cis_monthly%2Cname%2Curl" +
					"&fields[post]=change_visibility_at%2Ccomment_count%2Ccommenter_count%2Ccontent%2Ccurrent_user_can_comment%2Ccurrent_user_can_delete%2Ccurrent_user_can_view%2Ccurrent_user_has_liked%2Cembed%2Cimage%2Cinsights_last_updated_at%2Cis_paid%2Clike_count%2Cmeta_image_url%2Cmin_cents_pledged_to_view%2Cpost_file%2Cpost_metadata%2Cpublished_at%2Cpatreon_url%2Cpost_type%2Cpledge_url%2Cpreview_asset_type%2Cthumbnail%2Cthumbnail_url%2Cteaser_text%2Ctitle%2Cupgrade_url%2Curl%2Cwas_posted_by_campaign_owner%2Chas_ti_violation%2Cmoderation_status%2Cpost_level_suspension_removal_date%2Cpls_one_liners_by_category%2Cvideo_preview%2Cview_count" +
					"&fields[post_tag]=tag_type%2Cvalue" +
					"&fields[user]=image_url%2Cfull_name%2Curl" +
					"&fields[access_rule]=access_rule_type%2Camount_cents" +
					"&fields[media]=id%2Cimage_urls%2Cdownload_url%2Cmetadata%2Cfile_name" +
					"&fields[native_video_insights]=average_view_duration%2Caverage_view_pct%2Chas_preview%2Cid%2Clast_updated_at%2Cnum_views%2Cpreview_views%2Cvideo_duration" +
					"&filter[campaign_id]=" + campaignId +
					"&filter[contains_exclusive_posts]=true" +
					"&filter[is_draft]=false" +
					"&sort=-published_at" +
					"&json-api-version=1.0";
			this.postsIt = Collections.emptyIterator();
			this.currentResourcesIncluded = null;
			//this.nextPageUrl += "&filter[month]=2018-8";
			computeNextPost();
		}
		
		@Override
		protected Post findNextPost() throws Exception
		{
			if (postsIt.hasNext())
			{
				JsonElement jPost = postsIt.next();
				
				boolean canView = JsonHelper.followPath(jPost, "attributes.current_user_can_view", boolean.class);
				if (!canView)
					return findNextPost();
				
				String id = JsonHelper.followPath(jPost, "id");
				String title = Objects.toString(JsonHelper.followPath(jPost, "attributes.title"), "");
				ZonedDateTime publishedDatetime = ZonedDateTime.parse(JsonHelper.followPath(jPost, "attributes.published_at"));
				
				return Post.create(id, title, publishedDatetime, new PostExtraInfo(jPost, currentResourcesIncluded));
			}
			else if (nextPageUrl != null)
			{
				HttpRequest request = HttpRequest.newBuilder()
				                    .uri(new URI(nextPageUrl))
				                    .GET()
				                    .headers(session.getExtraInfo(HEADERS_KEY))
				                    .build();
				JsonElement response = session.send(request, JsonHelper.httpBodyHandler()).body();
				
				nextPageUrl = JsonHelper.followPath(response, "links.next");
				postsIt = JsonHelper.followPath(response, "data", JsonArray.class).iterator();
				
				currentResourcesIncluded = new HashMap<>();
				JsonHelper.followPath(response, "included", JsonArray.class).forEach(item ->
				{
					String id = JsonHelper.followPath(item, "id");
					String type = JsonHelper.followPath(item, "type");
					currentResourcesIncluded.put(new RessourcesId(id, type), item);
				});
				
				return findNextPost();
			}
			else
			{
				return null;
			}
		}
	}
	
	@Override
	protected CompletableFuture<List<PostImage>> listImages(DownloadSession session, Post post)
	{
		List<JsonElement> jImages = getRelationshipElements(post, "images");
		List<PostImage> images;
		if (jImages.isEmpty())
		{
			images = List.of();
		}
		else
		{
			images = new ArrayList<>(jImages.size());
			Collection<String> tags = getPostTags(post);
			
			for (JsonElement jImage : jImages)
			{
				String id = JsonHelper.followPath(jImage, "id");
				String filename = JsonHelper.followPath(jImage, "attributes.file_name");
				String url = JsonHelper.followPath(jImage, "attributes.image_urls.original");
				
				images.add(PostImage.create(id, filename, url, tags));
			}
			
			List<String> idsOrder = JsonHelper.stream(JsonHelper.followPath(((PostExtraInfo) post.extraInfo()).jPost(),
			                                                                "attributes.post_metadata.image_order",
			                                                                JsonArray.class))
			                                  .map(JsonElement::getAsString)
			                                  .toList();
			images.sort(Comparator.comparing(PostImage::id, Comparator.comparingInt(idsOrder::indexOf)));
		}
		
		return CompletableFuture.completedFuture(images);
	}
	
	@Override
	protected CompletableFuture<List<PostFile>> listFiles(DownloadSession session, Post post) throws Exception
	{
		List<JsonElement> jFiles = getRelationshipElements(post, "attachments");
		List<PostFile> files;
		if (jFiles.isEmpty())
		{
			files = List.of();
		}
		else
		{
			files = new ArrayList<>(jFiles.size());
			Collection<String> tags = getPostTags(post);
			
			for (JsonElement jFile : jFiles)
			{
				String id = JsonHelper.followPath(jFile, "id");
				String filename = JsonHelper.followPath(jFile, "attributes.name");
				String url = JsonHelper.followPath(jFile, "attributes.url");
				
				files.add(PostFile.create(id, filename, url, tags));
			}
		}
		
		return CompletableFuture.completedFuture(files);
	}
	
	private Collection<String> getPostTags(Post post)
	{
		return getRelationshipElements(post, "user_defined_tags")
				.stream()
				.map(jTag -> JsonHelper.followPath(jTag, "attributes.value"))
				.toList();
	}
	
	private List<JsonElement> getRelationshipElements(Post post, String relationship)
	{
		PostExtraInfo extraInfo = (PostExtraInfo) post.extraInfo();
		JsonElement jPost = extraInfo.jPost();
		Map<RessourcesId, JsonElement> resources = extraInfo.resources();
		
		JsonArray jElementsRef = JsonHelper.followPath(jPost,
		                                               "relationships." + relationship + ".data",
		                                               JsonArray.class);
		
		ArrayList<JsonElement> elements = new ArrayList<>();
		if (jElementsRef != null)
		{
			elements.ensureCapacity(jElementsRef.size());
			
			for (JsonElement jElementRef : jElementsRef)
			{
				String id = JsonHelper.followPath(jElementRef, "id");
				String type = JsonHelper.followPath(jElementRef, "type");
				JsonElement jElement = resources.get(new RessourcesId(id, type));
				
				
				
				elements.add(jElement);
			}
		}
		
		return elements;
	}

	@Override
	protected String[] getHeardersForImageDownload(DownloadSession session, PostImage image)
	{
		return session.getExtraInfo(HEADERS_KEY);
	}
	
	@Override
	protected String[] getHeardersForFileDownload(DownloadSession session, PostFile image)
	{
		return session.getExtraInfo(HEADERS_KEY);
	}
	
	private String[] getWebUiHeaders()
	{
		// @formatter:off
		return new String[] {
				"User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:108.0) Gecko/20100101 Firefox/108.0",
				"Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
				"Accept-Language", "fr,fr-FR;q=0.8,en-US;q=0.5,en;q=0.3",
				"DNT", "1",
				"Upgrade-Insecure-Requests", "1",
				"Sec-Fetch-Dest", "document",
				"Sec-Fetch-Mode", "navigate",
				"Sec-Fetch-Site", "none",
				"Sec-Fetch-User", "?1"
		};
		// @formatter:on
	}
	
	private String[] getHeaders(DownloadSession session)
	{
		// @formatter:off
		return new String[] {
			"accept", "*/*",
			"accept-encoding", "gzip, deflate",
			"accept-language", "fr-FR,fr;q=0.9,en-US;q=0.8,en;q=0.7,ja;q=0.6",
			"content-type", "application/vnd.api+json",
			"cookie", session.getSecret("patreon.cookie"),
			"referer", "https://www.patreon.com/" + creatorId + "/posts",
			"sec-ch-device-memory", "8",
			"sec-ch-ua", "\"Not?A_Brand\";v=\"8\", \"Chromium\";v=\"108\", \"Google Chrome\";v=\"108\"",
			"sec-ch-ua-arch", "\"x86\"",
			"sec-ch-ua-full-version-list", "\"Not?A_Brand\";v=\"8.0.0.0\", \"Chromium\";v=\"108.0.5359.100\", \"Google Chrome\";v=\"108.0.5359.100\"",
			"sec-ch-ua-mobile", "?0",
			"sec-ch-ua-model" , "",
			"sec-ch-ua-platform", "\"Windows\"",
			"sec-fetch-dest", "empty",
			"sec-fetch-mode", "cors",
			"sec-fetch-site", "same-origin",
			"user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/108.0.0.0 Safari/537.36"
		};
		// @formatter:on
	}
}
