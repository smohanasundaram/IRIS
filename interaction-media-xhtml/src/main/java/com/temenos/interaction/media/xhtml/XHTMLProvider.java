package com.temenos.interaction.media.xhtml;

/*
 * #%L
 * interaction-media-xhtml
 * %%
 * Copyright (C) 2012 - 2013 Temenos Holdings N.V.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */


import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.ws.rs.Consumes;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.UriInfo;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;

import org.odata4j.core.OEntity;
import org.odata4j.core.OProperty;
import org.odata4j.edm.EdmDataServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cambridge.Template;
import cambridge.TemplateEvaluationException;
import cambridge.TemplateFactory;

import com.temenos.interaction.core.entity.Entity;
import com.temenos.interaction.core.entity.EntityMetadata;
import com.temenos.interaction.core.entity.EntityProperty;
import com.temenos.interaction.core.entity.GenericError;
import com.temenos.interaction.core.entity.Metadata;
import com.temenos.interaction.core.hypermedia.Link;
import com.temenos.interaction.core.resource.CollectionResource;
import com.temenos.interaction.core.resource.EntityResource;
import com.temenos.interaction.core.resource.RESTResource;
import com.temenos.interaction.core.resource.ResourceTypeHelper;

@Provider
@Consumes({MediaType.APPLICATION_XHTML_XML})
@Produces({MediaType.APPLICATION_XHTML_XML, MediaType.TEXT_HTML})
public class XHTMLProvider implements MessageBodyReader<RESTResource>, MessageBodyWriter<RESTResource> {
	private final Logger logger = LoggerFactory.getLogger(XHTMLProvider.class);

	@Context
	private UriInfo uriInfo;
	private Metadata metadata = null;
	private XHTMLTemplateFactories templateFactories = new XHTMLTemplateFactories();
	
	public XHTMLProvider(Metadata metadata) {
		this.metadata = metadata;
		assert(metadata != null);
	}
	
	@Override
	public boolean isWriteable(Class<?> type, Type genericType,
			Annotation[] annotations, MediaType mediaType) {
		return ResourceTypeHelper.isType(type, genericType, EntityResource.class) ||
				ResourceTypeHelper.isType(type, genericType, CollectionResource.class);
	}

	@Override
	public long getSize(RESTResource t, Class<?> type, Type genericType,
			Annotation[] annotations, MediaType mediaType) {
		return -1;
	}

	/**
	 * Writes a XHTML representation of
	 * {@link EntityResource} to the output stream.
	 * 
	 * @precondition supplied {@link EntityResource} is non null
	 * @precondition {@link EntityResource#getEntity()} returns a valid OEntity, this 
	 * provider only supports serialising OEntities
	 * @postcondition non null XHTML document written to OutputStream
	 * @invariant valid OutputStream
	 */
	@Override
	public void writeTo(RESTResource resource, Class<?> type, Type genericType,
			Annotation[] annotations, MediaType mediaType,
			MultivaluedMap<String, Object> httpHeaders,
			OutputStream entityStream) throws IOException,
			WebApplicationException {
		assert (resource != null);
		logger.debug("Writing " + mediaType);
		Writer writer = new BufferedWriter(new OutputStreamWriter(entityStream, "UTF-8"));
		try {
			renderResource(writer, resource, mediaType, type, genericType);
			writer.flush();
		} catch(TemplateEvaluationException tee) {
			String msg = "Failed to render XHTML response on tag " + tee.getTagName() + " [line " + tee.getLine() + ", column " + tee.getColumn() + "]";
			logger.error(msg + ": " + tee.getMessage());
			throw new WebApplicationException(createErrorResponse(mediaType, msg));
		} catch(Exception e) {
			logger.error("Failed to render XHTML response: " + e.getMessage());
			throw new WebApplicationException(createErrorResponse(mediaType, e.getMessage()));
		}
	}
	
	/*
	 * Render this resource to the output stram 
	 * @param writer output stream
	 * @param resource resource to render
	 * @param mediaType media type
	 * @param type resource type
	 * @param genericType resource generic type
	 * @throws Exception
	 */
	@SuppressWarnings("unchecked")
	private void renderResource(Writer writer, RESTResource resource, MediaType mediaType, Class<?> type, Type genericType) throws Exception {
		// create the xhtml resource
		if (resource.getGenericEntity() != null) {
			RESTResource rResource = (RESTResource) resource.getGenericEntity().getEntity();
			Collection<Link> links = rResource.getLinks();
			if(links == null) {
				links = new ArrayList<Link>();
			}

			//Render header
			Template template = getTemplate(mediaType.equals(MediaType.APPLICATION_XHTML_XML_TYPE) ? XHTMLTemplateFactories.TEMPLATE_HEADER_MINIMAL : XHTMLTemplateFactories.TEMPLATE_HEADER );
			template.setProperty("siteName", uriInfo != null && uriInfo.getPath() != null ? uriInfo.getPath() : "");
			template.setProperty("resourceLinks", links);
			template.printTo(writer);
			
			//links are already in header - render for text/html
			if (mediaType.equals(MediaType.TEXT_HTML_TYPE)) {
				template = getTemplate(XHTMLTemplateFactories.TEMPLATE_RESOURCE_LINKS);
				template.setProperty("resourceLinks", links);
				template.printTo(writer);
			}
			
			//render data
			if (ResourceTypeHelper.isType(type, genericType, EntityResource.class)) {
				template = getTemplate(mediaType.equals(MediaType.APPLICATION_XHTML_XML_TYPE) ? XHTMLTemplateFactories.TEMPLATE_ENTITY_MINIMAL : XHTMLTemplateFactories.TEMPLATE_ENTITY);
				if (ResourceTypeHelper.isType(type, genericType, EntityResource.class, OEntity.class)) {
					//OEntity entity resource
					EntityResource<OEntity> oentityResource = (EntityResource<OEntity>) resource;
					EntityMetadata entityMetadata = metadata.getEntityMetadata(oentityResource.getEntityName());
					template.setProperty("entityResource", new EntityResourceWrapperXHTML(entityMetadata, buildFromOEntity(oentityResource)));
					template.printTo(writer);
				} else if (ResourceTypeHelper.isType(type, genericType, EntityResource.class, Entity.class)) {
					//Entity entity resource
					EntityResource<Entity> entityResource = (EntityResource<Entity>) resource;
					EntityMetadata entityMetadata = metadata.getEntityMetadata(entityResource.getEntityName() != null ? entityResource.getEntityName() : entityResource.getEntity().getName());
					template.setProperty("entityResource", new EntityResourceWrapperXHTML(entityMetadata, buildFromEntity(entityResource)));
					template.printTo(writer);
				} else if (ResourceTypeHelper.isType(type, genericType, EntityResource.class, EdmDataServices.class)) {
					//The resources are shown in the resource links section
				} else if (ResourceTypeHelper.isType(type, genericType, EntityResource.class, GenericError.class)) {
					//Generic error error
					EntityResource<GenericError> entityResource = (EntityResource<GenericError>) resource;
					GenericError error = entityResource.getEntity();
					writer.write(getErrorMessage(mediaType,  "[" + error.getCode() + "] " + error.getMessage()));
				} else if (ResourceTypeHelper.isType(type, genericType, EntityResource.class)) {
					//JAXB entity resource
					EntityResource<Object> entityResource = (EntityResource<Object>) resource;
					EntityMetadata entityMetadata = metadata.getEntityMetadata(entityResource.getEntityName());
					if(entityResource.getEntity() != null) {
						template.setProperty("entityResource", new EntityResourceWrapperXHTML(entityMetadata, buildFromBean(entityResource)));
						template.printTo(writer);
					}
				} else {
					logger.error("Accepted object for writing in isWriteable, but type not supported in writeTo method");
					throw new Exception("Unable to render this resource as an XHTML response.");
				}
			}
			else if (ResourceTypeHelper.isType(type, genericType, CollectionResource.class)) {
				template = getTemplate(mediaType.equals(MediaType.APPLICATION_XHTML_XML_TYPE) ? XHTMLTemplateFactories.TEMPLATE_ENTITIES_MINIMAL : XHTMLTemplateFactories.TEMPLATE_ENTITIES);
				if (ResourceTypeHelper.isType(type, genericType, CollectionResource.class, Entity.class)) {
					//Entity collection resource
					CollectionResource<Entity> collectionResource = (CollectionResource<Entity>) resource;
					EntityMetadata entityMetadata = metadata.getEntityMetadata(collectionResource.getEntityName());
					Set<String> entityPropertyNames = entityMetadata.getTopLevelProperties();
					List<EntityResource<Entity>> entityResources = (List<EntityResource<Entity>>) collectionResource.getEntities();
					List<EntityResourceWrapperXHTML> entities = new ArrayList<EntityResourceWrapperXHTML>();
					for (EntityResource<Entity> er : entityResources) {
						entities.add(new EntityResourceWrapperXHTML(entityMetadata, entityPropertyNames, buildFromEntity(er)));
					}
					template.setProperty("entitySetName", collectionResource.getEntitySetName());
					template.setProperty("entityPropertyNames", entityPropertyNames);
					template.setProperty("entityResources", entities);
				} else if(ResourceTypeHelper.isType(type, genericType, CollectionResource.class, OEntity.class)) {
					//OEntity collection resource
					CollectionResource<OEntity> collectionResource = ((CollectionResource<OEntity>) resource);
					EntityMetadata entityMetadata = metadata.getEntityMetadata(collectionResource.getEntityName());
					Set<String> entityPropertyNames = entityMetadata.getTopLevelProperties();
					List<EntityResource<OEntity>> entityResources = (List<EntityResource<OEntity>>) collectionResource.getEntities();
					List<EntityResourceWrapperXHTML> entities = new ArrayList<EntityResourceWrapperXHTML>();
					for (EntityResource<OEntity> er : entityResources) {
						entities.add(new EntityResourceWrapperXHTML(entityMetadata, entityPropertyNames, buildFromOEntity(er)));
					}
					template.setProperty("entitySetName", collectionResource.getEntitySetName());
					template.setProperty("entityPropertyNames", entityPropertyNames);
					template.setProperty("entityResources", entities);
				} else if (ResourceTypeHelper.isType(type, genericType, CollectionResource.class)) {
					//JAXB collection resource
					CollectionResource<Object> collectionResource = (CollectionResource<Object>) resource;
					List<EntityResource<Object>> entityResources = (List<EntityResource<Object>>) collectionResource.getEntities();
					List<EntityResourceWrapperXHTML> entities = new ArrayList<EntityResourceWrapperXHTML>();
					EntityMetadata entityMetadata = metadata.getEntityMetadata(collectionResource.getEntityName());
					Set<String> entityPropertyNames = entityMetadata.getTopLevelProperties();
					for (EntityResource<Object> er : entityResources) {
						er.setEntityName(collectionResource.getEntityName());
						entities.add(new EntityResourceWrapperXHTML(entityMetadata, entityPropertyNames, buildFromBean(er)));
					}
					template.setProperty("entitySetName", collectionResource.getEntitySetName());
					template.setProperty("entityPropertyNames", entityPropertyNames);
					template.setProperty("entityResources", entities);
				} else {
					logger.error("Accepted object for writing in isWriteable, but type not supported in writeTo method");
					throw new Exception("Unable to render this resource as an XHTML response.");
				}
				template.printTo(writer);
			} else {
				logger.error("Accepted object for writing in isWriteable, but type not supported in writeTo method");
				throw new Exception("Unable to render this resource as an XHTML response.");
			}

			//Render footer
			getTemplate(XHTMLTemplateFactories.TEMPLATE_FOOTER).printTo(writer);
		} else {
			logger.error("Unable to render empty resource.");
			throw new Exception("Unable to render this empty resource as an XHTML response.");
		}
	}
	
	protected Set<String> getEntityPropertyNames(String entityName, List<EntityResourceWrapperXHTML> entities) {
		if(entities.size() > 0) {
			return entities.get(0).getResource().getEntity().keySet();
		}
		else {
			return metadata.getEntityMetadata(entityName).getTopLevelProperties();			
		}

	}

	protected EntityResource<Map<String, Object>> buildFromOEntity(EntityResource<OEntity> entityResource) {
		OEntity entity = entityResource.getEntity();
		Map<String, Object> map = new HashMap<String, Object>();
		for (OProperty<?> property : entity.getProperties()) 
		{
			if(property != null) {
				map.put(property.getName(), property.getValue());				
			}
		}
		EntityResource<Map<String, Object>> er = new EntityResource<Map<String, Object>>(map);
		er.setLinks(entityResource.getLinks());
		er.setEntityName(entity.getEntitySetName());
		return er;
	}
	
	protected EntityResource<Map<String, Object>> buildFromEntity(EntityResource<Entity> entityResource) {
		Entity entity = entityResource.getEntity();
		Map<String, Object> map = new HashMap<String, Object>();
		Map<String, EntityProperty> properties = entity.getProperties().getProperties();
		for (String propertyName : properties.keySet()) 
		{
			EntityProperty propertyValue = (EntityProperty) properties.get(propertyName);
			if(propertyValue != null) {
				map.put(propertyName, propertyValue.getValue());	
			}
		}
		EntityResource<Map<String, Object>> er = new EntityResource<Map<String, Object>>(map);
		er.setLinks(entityResource.getLinks());
		er.setEntityName(entity.getName());
		return er;
	}

	protected EntityResource<Map<String, Object>> buildFromBean(EntityResource<Object> entityResource) {
		Map<String, Object> map = new HashMap<String, Object>();

		String entityName = entityResource.getEntityName();
		EntityMetadata entityMetadata = metadata.getEntityMetadata(entityName);
		if (entityMetadata == null)
			throw new IllegalStateException("Entity metadata could not be found [" + entityName + "]");

		try {
			BeanInfo beanInfo = Introspector.getBeanInfo(entityResource.getEntity().getClass());
			for (PropertyDescriptor propertyDesc : beanInfo.getPropertyDescriptors()) {
			    String propertyName = propertyDesc.getName();
				if (entityMetadata.getPropertyVocabulary(propertyName) != null) {
				    Object value = propertyDesc.getReadMethod().invoke(entityResource.getEntity());
					map.put(propertyName, value);				
				}
			}
		} catch (IllegalArgumentException e) {
			logger.error("Error accessing bean property", e);
		} catch (IntrospectionException e) {
			logger.error("Error accessing bean property", e);
		} catch (IllegalAccessException e) {
			logger.error("Error accessing bean property", e);
		} catch (InvocationTargetException e) {
			logger.error("Error accessing bean property", e);
		}

		EntityResource<Map<String, Object>> er = new EntityResource<Map<String, Object>>(map);
		er.setEntityName(entityName);
		er.setLinks(entityResource.getLinks());
		return er;
	}

	@Override
	public boolean isReadable(Class<?> type, Type genericType,
			Annotation[] annotations, MediaType mediaType) {
		// this class can only deserialise EntityResource
		return ResourceTypeHelper.isType(type, genericType, EntityResource.class);
	}

	/**
	 * Reads a Hypertext Application Language (HAL) representation of
	 * {@link EntityResource} from the input stream.
	 * 
	 * @precondition {@link InputStream} contains a valid HAL <resource/> document
	 * @postcondition {@link EntityResource} will be constructed and returned.
	 * @invariant valid InputStream
	 */
	@Override
	public RESTResource readFrom(Class<RESTResource> type, Type genericType,
			Annotation[] annotations, MediaType mediaType,
			MultivaluedMap<String, String> httpHeaders, InputStream entityStream)
			throws IOException, WebApplicationException {

		if (!ResourceTypeHelper.isType(type, genericType, EntityResource.class))
			throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
		
		return null;
	}
	
	/* Ugly testing support :-( */
	protected void setUriInfo(UriInfo uriInfo) {
		this.uriInfo = uriInfo;
	}
	
	/*
	 * Return the specified template factory
	 */
	private Template getTemplate(String templateFactoryName) throws WebApplicationException {
		TemplateFactory tf = templateFactories.getTemplateFactory(templateFactoryName);
		if(tf != null) {
			return tf.createTemplate();
		}
		else {
			logger.error("Failed to obtain template factory " + templateFactoryName);
			throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
		}
	}
	
	/*
	 * Render an error response
	 * @param mediaType media type
	 * @param errorMessage error message
	 * @return error response
	 */
	private Response createErrorResponse(MediaType mediaType, String errorMessage) {
		ResponseBuilder responseBuilder = Response.status(Response.Status.INTERNAL_SERVER_ERROR);
		String entity = "";
		if(mediaType.equals(MediaType.APPLICATION_XHTML_XML_TYPE)) {
			entity += getTemplate(XHTMLTemplateFactories.TEMPLATE_HEADER_MINIMAL).asString();
		}
		else {
			entity += getTemplate(XHTMLTemplateFactories.TEMPLATE_HEADER).asString();
		}
		entity += getErrorMessage(mediaType, errorMessage);
		entity += getTemplate(XHTMLTemplateFactories.TEMPLATE_FOOTER).asString();
		responseBuilder.entity(entity);
		responseBuilder.type(mediaType);
		return responseBuilder.build();
	}

	/*
	 * Return an error message
	 * @param mediaType media type
	 * @param errorMessage error message
	 * @return error response
	 */
	private String getErrorMessage(MediaType mediaType, String errorMessage) {
		if(mediaType.equals(MediaType.APPLICATION_XHTML_XML_TYPE)) {
			return "<error>" + errorMessage + "</error>";
		}
		else {
			return "<div class=\"error\">" + errorMessage + "</div>";
		}
	}
}
