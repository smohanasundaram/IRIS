package com.temenos.interaction.commands.odata.consumer;

import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;

import org.core4j.Enumerable;
import org.odata4j.consumer.ODataConsumer;
import org.odata4j.core.OEntity;
import org.odata4j.core.OQueryRequest;
import org.odata4j.edm.EdmDataServices;
import org.odata4j.edm.EdmEntitySet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.temenos.interaction.core.RESTResponse;
import com.temenos.interaction.core.command.ResourceGetCommand;
import com.temenos.interaction.core.resource.CollectionResource;

public class GETEntitiesCommand implements ResourceGetCommand {
	private final Logger logger = LoggerFactory.getLogger(GETEntitiesCommand.class);

	// Command configuration
	private String entitySetName;
	
	private ODataConsumer consumer;
	private EdmDataServices edmDataServices;
	private EdmEntitySet entitySet;

	public GETEntitiesCommand(String entitySetName, ODataConsumer consumer) {
		this.entitySetName = entitySetName;
		this.consumer = consumer;
		this.edmDataServices = consumer.getMetadata();
		this.entitySet = edmDataServices.getEdmEntitySet(entitySetName);
		assert(entitySet != null);
	}

	
	public RESTResponse get(String id, MultivaluedMap<String, String> queryParams) {
		logger.info("Getting entities for " + entitySet.getName());

		int top = getAsInt(queryParams.getFirst("$top"));
		int skip = getAsInt(queryParams.getFirst("$skip"));
		String filter = queryParams.getFirst("$filter");
		String orderBy = queryParams.getFirst("$orderby");
		String expand = queryParams.getFirst("$expand");
		String select = queryParams.getFirst("$select");

		/*
		 * Create request
		 */
		OQueryRequest<OEntity> request = consumer.getEntities(entitySetName);
		request
			.top(top)
			.skip(skip)
			.filter(filter)
			.orderBy(orderBy)
			.expand(expand)
			.select(select);
		
		/*
		 * Handle custom options if any
		 */
		for (String key : queryParams.keySet()){
			String value = queryParams.getFirst(key);
			if (!key.startsWith("$")){
				request.custom(key, value);
			}
		}
		
		/*
		 * Execute request
		 */
		Enumerable<OEntity> response = request.execute();
		    
		CollectionResource<OEntity> cr = CommandHelper.createCollectionResource(entitySetName, response.toList());
		RESTResponse rr = new RESTResponse(Response.Status.OK, cr);
		return rr;
	}
	
	public static int getAsInt(String value){
		try {
			int result = Integer.parseInt(value);
			return result;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

}