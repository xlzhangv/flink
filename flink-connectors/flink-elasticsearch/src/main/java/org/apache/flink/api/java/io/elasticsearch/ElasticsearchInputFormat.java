package org.apache.flink.api.java.io.elasticsearch;

import org.apache.flink.api.common.io.DefaultInputSplitAssigner;
import org.apache.flink.api.common.io.RichInputFormat;
import org.apache.flink.api.common.io.statistics.BaseStatistics;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.io.GenericInputSplit;
import org.apache.flink.core.io.InputSplit;
import org.apache.flink.core.io.InputSplitAssigner;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchScrollRequestBuilder;
import org.elasticsearch.client.transport.TransportClient;
import org.elasticsearch.common.network.NetworkModule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.transport.TransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.transport.Netty3Plugin;
import org.elasticsearch.transport.client.PreBuiltTransportClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * ES Input TODO.
 */
public class ElasticsearchInputFormat<T> extends RichInputFormat<T, InputSplit> implements ResultTypeQueryable<T> {

	private static final long serialVersionUID = 1L;
	private static final Logger LOG = LoggerFactory.getLogger(ElasticsearchInputFormat.class);

	private final BiConsumer<Map<String, Object>, T> esTypeMapper;

	private boolean hasNext;
	private int currHitIdx;


	/**
	 * User-provided transport addresses.
	 *
	 * <p>We are using {@link InetSocketAddress} because {@link TransportAddress} is not serializable in Elasticsearch 5.x.
	 */
	private List<InetSocketAddress> transportAddresses;
	private String queryString;
	/**
	 * The factory to configure the rest client.
	 */
	private TransportClient client;
	private TypeInformation<T> typeInfo;
	private String indexName;

	private int scrollTimeoutMs = 60000;
	private String clusterName = "elasticsearch";

	private transient SearchResponse searchScrollResponse;
	private transient SearchHit[] searchHits;
	private transient SearchScrollRequestBuilder scrollRequestBuilder;
	private transient String scrollId;

//  private List<HttpHost> httpHosts;
//	private RestClientBuilder restClientBuilder;
//	private RestClient client;


	public ElasticsearchInputFormat(BiConsumer<Map<String, Object>, T> esTypeMapper) {
		this.esTypeMapper = esTypeMapper;
	}

	@Override
	public void configure(Configuration parameters) {
	}

	public static List<TransportAddress> convertInetSocketAddresses(List<InetSocketAddress> inetSocketAddresses) {
		if (inetSocketAddresses == null) {
			return null;
		} else {
			List<TransportAddress> converted;
			converted = new ArrayList<>(inetSocketAddresses.size());
			for (InetSocketAddress address : inetSocketAddresses) {
				converted.add(new InetSocketTransportAddress(address));
			}
			return converted;
		}
	}

	public TransportClient createClient(Map<String, String> clientConfig) {
		Settings settings = Settings.builder()
			.put(clientConfig)
			.put(NetworkModule.HTTP_TYPE_KEY, Netty3Plugin.NETTY_HTTP_TRANSPORT_NAME)
			.put(NetworkModule.TRANSPORT_TYPE_KEY, Netty3Plugin.NETTY_TRANSPORT_NAME)
			.build();

		TransportClient transportClient = new PreBuiltTransportClient(settings);
		for (TransportAddress transport : convertInetSocketAddresses(transportAddresses)) {
			transportClient.addTransportAddress(transport);
		}

		// verify that we actually are connected to a cluster
		if (transportClient.connectedNodes().isEmpty()) {
			throw new RuntimeException("Elasticsearch client is not connected to any Elasticsearch nodes!");
		}

		if (LOG.isInfoEnabled()) {
			LOG.info("Created Elasticsearch TransportClient with connected nodes {}", transportClient.connectedNodes());
		}

		return transportClient;
	}


	@Override
	public void open(InputSplit split) throws IOException {
		//if (split != null) {
		//	throw new IllegalArgumentException("Using inputSplit is currently unsupported");
		//}

		HashMap<String, String> clientConfig = new HashMap<>();
		clientConfig.put("cluster.name", clusterName);
		client = createClient(clientConfig);

		client.listedNodes()
			.forEach(n -> {
				if(LOG.isDebugEnabled()) {
					LOG.debug("ES Node name: {}", n.getName());
				}
			});

		QueryBuilder queryBuilder = QueryBuilders.wrapperQuery(queryString);
		searchScrollResponse = client.prepareSearch(indexName)
			.setScroll(new TimeValue(scrollTimeoutMs))
			.setQuery(queryBuilder)
			.setSize(1)
			.get();

		scrollId = searchScrollResponse.getScrollId();

		scrollRequestBuilder = client
			.prepareSearchScroll(scrollId)
			.setScroll(new TimeValue(scrollTimeoutMs));

		searchHits = searchScrollResponse.getHits().getHits();
		hasNext = searchHits.length != 0;
		currHitIdx = 0;

//		HttpHost[] httpHostArr = new HttpHost[httpHosts.size()];
//		httpHosts.toArray(httpHostArr);
//
//		restClientBuilder = RestClient.builder(httpHostArr);
//		client = restClientBuilder.build();
//		client.performRequest()

		if (LOG.isDebugEnabled()) {
			LOG.debug("[ES] Executed ES Query: '{}'", queryString.toString());
			LOG.debug("[ES] Executed ES Query: '{}'", queryBuilder);
			LOG.debug("[ES] Got '{}' results", searchHits.length);
		}

	}

	@Override
	public void close() throws IOException {
		if (client != null) {
			client.close();
		}
	}

	@Override
	public BaseStatistics getStatistics(BaseStatistics cachedStatistics) throws IOException {
		return cachedStatistics;
	}

	@Override
	public InputSplit[] createInputSplits(int minNumSplits) throws IOException {
		return new GenericInputSplit[]{new GenericInputSplit(0, 1)};
	}

	@Override
	public InputSplitAssigner getInputSplitAssigner(InputSplit[] inputSplits) {
		return new DefaultInputSplitAssigner(inputSplits);
		//return null;
	}

	@Override
	public boolean reachedEnd() throws IOException {
		return !hasNext;
	}

	@Override
	public T nextRecord(T rowReuse) throws IOException {
		if (!hasNext) {
			return null;
		}

		SearchHit hit = searchHits[currHitIdx];
		if(LOG.isDebugEnabled()) {
			LOG.debug("ES Hit '{}'", hit.getSourceAsString());
		}

		esTypeMapper.accept(hit.getSource(), rowReuse);

		currHitIdx++;

		boolean currScrollPageEnded = (currHitIdx == searchHits.length);
		if (currScrollPageEnded) {

			searchScrollResponse = scrollRequestBuilder
				.execute()
				.actionGet();
			searchHits = searchScrollResponse.getHits().getHits();
			currHitIdx = 0;

			if (LOG.isDebugEnabled()) {
				LOG.debug("ES Current scroll ended '{}', fetching next page", scrollId);
			}
		}

		hasNext = currHitIdx != searchHits.length;

		return rowReuse;
	}

	@Override
	public TypeInformation<T> getProducedType() {
		return typeInfo;
	}

	public static <G> ElasticsearchInputFormatBuilder <G> buildElasticsearchInputFormat(BiConsumer<Map<String, Object>, G> esTypeMapper) {
		return new ElasticsearchInputFormatBuilder<>(esTypeMapper);
	}

	/**
	 * Builder TODO.
	 */
	public static class ElasticsearchInputFormatBuilder<V> {
		private final ElasticsearchInputFormat<V> inputFormat;

		public ElasticsearchInputFormatBuilder(BiConsumer<Map<String, Object>, V> esTypeMapper) {
			this.inputFormat = new ElasticsearchInputFormat<>(esTypeMapper);
		}

		public ElasticsearchInputFormatBuilder<V> setTransportAddresses(List<InetSocketAddress> addresses) {
			inputFormat.transportAddresses = addresses;
			return this;
		}

		public ElasticsearchInputFormatBuilder<V> setQueryBuilder(QueryBuilder qb) {
			inputFormat.queryString = qb.toString();
			return this;
		}

		public ElasticsearchInputFormatBuilder<V> setIndex(String indexName) {
			inputFormat.indexName = indexName;
			return this;
		}

		public ElasticsearchInputFormatBuilder<V> setTypeInfo(TypeInformation<V> typeInfo) {
			inputFormat.typeInfo = typeInfo;
			return this;
		}

		public ElasticsearchInputFormatBuilder<V> setScrollTimeout(int scrollTimeoutMs) {
			inputFormat.scrollTimeoutMs = scrollTimeoutMs;
			return this;
		}

		public ElasticsearchInputFormatBuilder<V> setClusterName(String clusterName) {
			inputFormat.clusterName = clusterName;
			return this;
		}

		public ElasticsearchInputFormat<V> build() {
			return inputFormat;
		}
	}
}
