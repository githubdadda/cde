/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

package pt.webdetails.cdf.dd.structure;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;

import net.sf.json.JSON;
import net.sf.json.JSONObject;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;

import pt.webdetails.cdf.dd.Messages;
import pt.webdetails.cdf.dd.util.CdeEnvironment;
import pt.webdetails.cdf.dd.util.JsonUtils;
import pt.webdetails.cdf.dd.DashboardManager;
import pt.webdetails.cdf.dd.model.meta.MetaModel;
import pt.webdetails.cdf.dd.MetaModelManager;
import pt.webdetails.cdf.dd.model.meta.PropertyType;
import pt.webdetails.cdf.dd.model.core.UnsupportedThingException;
import pt.webdetails.cdf.dd.model.core.reader.ThingReadException;
import pt.webdetails.cdf.dd.model.core.validation.ValidationException;
import pt.webdetails.cdf.dd.model.core.writer.DefaultThingWriteContext;
import pt.webdetails.cdf.dd.model.core.writer.IThingWriteContext;
import pt.webdetails.cdf.dd.model.core.writer.IThingWriter;
import pt.webdetails.cdf.dd.model.core.writer.IThingWriterFactory;
import pt.webdetails.cdf.dd.model.core.writer.ThingWriteException;
import pt.webdetails.cdf.dd.model.inst.Dashboard;
import pt.webdetails.cdf.dd.model.inst.writer.cggrunjs.CggRunJsDashboardWriteContext;
import pt.webdetails.cdf.dd.model.inst.writer.cggrunjs.CggRunJsThingWriterFactory;
import pt.webdetails.cdf.dd.model.meta.IPropertyTypeSource;
import pt.webdetails.cdf.dd.model.meta.WidgetComponentType;
import pt.webdetails.cdf.dd.model.meta.writer.cdexml.XmlThingWriterFactory;
import pt.webdetails.cdf.dd.render.CdaRenderer;
import pt.webdetails.cpf.repository.api.IUserContentAccess;

public class DashboardStructure implements IDashboardStructure {
  private static final String ENCODING = "UTF-8";
  
  private static Log logger = LogFactory.getLog(DashboardStructure.class);
  
  public static String SYSTEM_PLUGIN_EMPTY_STRUCTURE_FILE_PATH = "resources/empty-structure.json";
  public static String SYSTEM_PLUGIN_EMPTY_WCDF_FILE_PATH = "resources/empty.wcdf";
  
  public DashboardStructure() {
  }

  public void delete(HashMap<String, Object> parameters) throws DashboardStructureException {
    // 1. Delete File
    String filePath = (String)parameters.get("file");
    
    logger.info("Deleting File:" + filePath);
    
    if(!CdeEnvironment.getUserContentAccess().deleteFile(filePath)) {
      throw new DashboardStructureException(Messages.getString("DashboardStructure.ERROR_007_DELETE_FILE_EXCEPTION"));
    }
  }
  
  public JSON load(HashMap<String, Object> parameters) throws Exception {
    JSONObject result = null;

    InputStream file = null;
    InputStream wcdfFile = null;
    try {
      String cdeFilePath = (String)parameters.get("file");
      
      logger.info("Loading File:" + cdeFilePath);
    
      // 1. Read .CDFDE file
      
      IUserContentAccess access = CdeEnvironment.getUserContentAccess();
      
      if(access.fileExists(cdeFilePath)) {
        file = access.getFileInputStream(cdeFilePath);
      
      } else {
    	
        file = CdeEnvironment.getPluginSystemReader().getFileInputStream(SYSTEM_PLUGIN_EMPTY_STRUCTURE_FILE_PATH);
      }

      JSON cdeData = JsonUtils.readJsonFromInputStream(file);
      
      // 3. Read .WCDF
      String wcdfFilePath = cdeFilePath.replace(".cdfde", ".wcdf");
      
      JSONObject wcdfData = loadWcdfDescriptor(wcdfFilePath).toJSON();
      
      result = new JSONObject();
      result.put("wcdf", wcdfData);
      result.put("data", cdeData);
    } catch (Throwable t) {
      throw new DashboardStructureException(Messages.getString("DashboardStructure.ERROR_003_LOAD_READING_FILE_EXCEPTION"));
    } finally {
      IOUtils.closeQuietly(file);
      IOUtils.closeQuietly(wcdfFile);
    }

    return result;
  }

  public DashboardWcdfDescriptor loadWcdfDescriptor(String wcdfFilePath) throws IOException {
    DashboardWcdfDescriptor wcdf  = DashboardWcdfDescriptor.load(wcdfFilePath);
    
    return wcdf != null ? wcdf : new DashboardWcdfDescriptor();
  }
  
  public DashboardWcdfDescriptor loadWcdfDescriptor(Document wcdfDoc) {
    return DashboardWcdfDescriptor.fromXml(wcdfDoc);
  }

  public HashMap<String, String> save(HashMap<String, Object> parameters) throws Exception {
    final HashMap<String, String> result = new HashMap<String, String>();
    
    // 1. Get CDE file parameters
    String cdeFilePath = (String)parameters.get("file");
    
    logger.info("Saving File:" + cdeFilePath);

    // 2. If not the CDE temp file, delete the temp file, if one exists
    IUserContentAccess access = CdeEnvironment.getUserContentAccess();
    
    boolean isPreview = cdeFilePath.indexOf("_tmp.cdfde") >= 0;
    if(!isPreview) {
      String cdeTempFilePath = cdeFilePath.replace(".cdfde", "_tmp.cdfde");
      access.deleteFile(cdeTempFilePath);
      
      String cdaTempFilePath = cdeFilePath.replace(".cdfde", "_tmp.cda");
      access.deleteFile(cdaTempFilePath);
    }
    
    // 3. CDE
    String cdfdeJsText = (String)parameters.get("cdfstructure");

    if (!access.saveFile(cdeFilePath, new ByteArrayInputStream(safeGetEncodedBytes(cdfdeJsText)))) {
      throw new DashboardStructureException(Messages.getString("DashboardStructure.ERROR_006_SAVE_FILE_ADD_FAIL_EXCEPTION"));
    }

    // 3. CDA
    CdaRenderer cdaRenderer = new CdaRenderer(cdfdeJsText);
    
    String cdaFileName = cdeFilePath.replace(".cdfde", ".cda");
    
    // Any data sources?
    if(cdaRenderer.isEmpty()) {
    	access.deleteFile(cdaFileName);
   
    } else {
      // throws Exception ????
      String cdaText = cdaRenderer.render();
      if(!access.saveFile(cdaFileName, new ByteArrayInputStream(safeGetEncodedBytes(cdaText)))) {
        throw new DashboardStructureException(Messages.getString("DashboardStructure.ERROR_006_SAVE_FILE_ADD_FAIL_EXCEPTION"));
      }
    }
    
    if(!isPreview) {
      String wcdfFilePath = cdeFilePath.replace(".cdfde", ".wcdf");
    
      // 4. When the component is a widget,
      //    and its internal "structure" has changed,
      //    Then any dashboard where it is used and 
      //    whose render result is cached 
      //    must be invalidated.
      DashboardManager.getInstance().invalidateDashboard(wcdfFilePath);

      // 5. CGG (requires an updated Dashboard instance)
      this.saveCgg(access, wcdfFilePath);
    }
    
    // TODO: Is this used?
    result.put("cdfde", "true");
    result.put("cda",   "true");
    result.put("cgg",   "true");
    
    return result;
  }
  
  private void saveCgg(IUserContentAccess access, String cdeRelFilePath) 
          throws ThingReadException, UnsupportedThingException, ThingWriteException {
    String wcdfFilePath = cdeRelFilePath.replace(".cdfde", ".wcdf");
    
    // Obtain an UPDATED dashboard object
    DashboardManager dashMgr = DashboardManager.getInstance();
    Dashboard dash = dashMgr.getDashboard(wcdfFilePath, /*bypassCacheRead*/false);
    
    CggRunJsThingWriterFactory cggWriteFactory = new CggRunJsThingWriterFactory();
    IThingWriter cggDashWriter = cggWriteFactory.getWriter(dash);
    CggRunJsDashboardWriteContext cggDashContext = new CggRunJsDashboardWriteContext(cggWriteFactory, dash);
    cggDashWriter.write(access, cggDashContext, dash);
  }
  
  public void saveas(HashMap<String, Object> parameters) throws Exception {
    // TODO: This method does not maintain the Widget status and parameters of a dashboard
    // Is this intended?
    
    // 1. Read empty wcdf file
    InputStream wcdfFile = CdeEnvironment.getPluginSystemReader().getFileInputStream(SYSTEM_PLUGIN_EMPTY_WCDF_FILE_PATH);
    
    String wcdfContentAsString = IOUtils.toString(wcdfFile, ENCODING);

    // 2. Fill-in wcdf file title and description
    String title = StringUtils.defaultIfEmpty((String)parameters.get("title"), "Dashboard");
    String description = StringUtils.defaultIfEmpty((String)parameters.get("description"), "");

    wcdfContentAsString = wcdfContentAsString.replaceFirst("@DASBOARD_TITLE@", title).replaceFirst("@DASBOARD_DESCRIPTION@", description);

    String filePath = (String)parameters.get("file");

    // 3. Publish new wcdf file
    if(!CdeEnvironment.getUserContentAccess().saveFile(filePath, new ByteArrayInputStream(wcdfContentAsString.getBytes(ENCODING)))) {
      throw new DashboardStructureException(Messages.getString("DashboardStructure.ERROR_005_SAVE_PUBLISH_FILE_EXCEPTION"));
    }
    
    // 4. Save cdf structure
    parameters.put("file", filePath.replace(".wcdf", ".cdfde"));
    
    save(parameters);
  }

  public void newfile(HashMap<String, Object> parameters) throws Exception {
    // 1. Read Empty Structure
    InputStream cdfstructure = null;
    try {
    	cdfstructure = CdeEnvironment.getPluginSystemReader().getFileInputStream(SYSTEM_PLUGIN_EMPTY_STRUCTURE_FILE_PATH);

      // 2. Save file
      parameters.put("cdfstructure", JsonUtils.readJsonFromInputStream(cdfstructure).toString());
      
      saveas(parameters);
      
    } finally {
      IOUtils.closeQuietly(cdfstructure);
    }
  }

  // .WCDF file
  public void savesettings(HashMap<String, Object> parameters) throws DashboardStructureException {
    String wcdfFilePath = (String)parameters.get("file");
    logger.info("Saving settings file:" + wcdfFilePath);

    DashboardWcdfDescriptor wcdf = null;
    try {
      wcdf = DashboardWcdfDescriptor.load(wcdfFilePath);
    
    } catch(IOException ex) {
      // Access?
      throw new DashboardStructureException(Messages.getString("DashboardStructure.ERROR_009_SAVE_SETTINGS_FILENOTFOUND_EXCEPTION"));
    }

    if(wcdf == null) {
      throw new DashboardStructureException(Messages.getString("DashboardStructure.ERROR_009_SAVE_SETTINGS_FILENOTFOUND_EXCEPTION"));
    }

    // Update with client info
    wcdf.update(parameters);
    
    // Save to repository
    String wcdfText = wcdf.toXml().asXML();
    if(!CdeEnvironment.getUserContentAccess().saveFile(wcdfFilePath, new ByteArrayInputStream(safeGetEncodedBytes(wcdfText)))) {
      throw new DashboardStructureException(Messages.getString("DashboardStructure.ERROR_010_SAVE_SETTINGS_FAIL_EXCEPTION"));
    }
    
    // Save widget component.xml file?
    if(wcdf.isWidget()) {
      publishWidgetComponentXml(wcdf);
    }
  }

  private void publishWidgetComponentXml(DashboardWcdfDescriptor wcdf) {
    String widgetPath = wcdf.getPath().replaceAll(".wcdf$", ".component.xml");
    
    logger.info("Saving widget component file:" + widgetPath);
    
    Document doc = createAndWriteWidgetComponentTypeXml(wcdf);
    if(doc == null) {
      // Failed
      return;
    }

    CdeEnvironment.getPluginSystemWriter().saveFile(widgetPath, new ByteArrayInputStream(safeGetEncodedBytes(doc.asXML())));
    
    // This will allow the metadata model to receive the
    // new/updated widget-component definition (name and parameters).
    // The CDE Editor will show new/updated widgets.
    // No need to refresh data source definitions.
    try {
      DashboardManager.getInstance().refreshAll(/*refreshDatasources*/false);
    } catch(Exception ex)  {
      logger.error("Error while refreshing the meta data cache", ex);
    }
  }

  private static Document createAndWriteWidgetComponentTypeXml(DashboardWcdfDescriptor wcdf)
  {
    WidgetComponentType widget  = createWidgetComponentType(wcdf);
    if(widget == null)
    {
      return null;
    }
    
    IThingWriterFactory factory = new XmlThingWriterFactory();
    IThingWriteContext context  = new DefaultThingWriteContext(factory, true);

    IThingWriter writer;
    try
    {
      writer = factory.getWriter(widget);
    }
    catch(UnsupportedThingException ex)
    {
      logger.error("No writer to write widget component type to XML", ex);
      return null;
    }

    Document doc = DocumentHelper.createDocument();
    try
    {
      writer.write(doc, context, widget);
    }
    catch (ThingWriteException ex)
    {
      logger.error("Failed writing widget component type to XML", ex);
      return null;
    }

    return doc;
  }
  
  private static WidgetComponentType createWidgetComponentType(DashboardWcdfDescriptor wcdf)
  {
    WidgetComponentType.Builder builder = new WidgetComponentType.Builder();
    String name = wcdf.getWidgetName();
    builder
      .setName("widget" + name)
      .setLabel(name)
      // TODO: Consider using wcdf.getDescription() directly?
      .setTooltip(name + " Widget")
      .setCategory("WIDGETS")
      .setCategoryLabel("Widgets")
      .addAttribute("widget", "true")
      .addAttribute("wcdf", wcdf.getPath());

    builder.useProperty(null, "htmlObject");

    for(String paramName : wcdf.getWidgetParameters())
    {
      // Create an *own* property
      PropertyType.Builder prop = new PropertyType.Builder();

      // valueType is String
      prop
        .setName(paramName)
        .setLabel("Parameter " + paramName)
        .setTooltip("What dashboard parameter should map to widget parameter '" + paramName + "'?");

      prop.setInputType("Parameter");

      builder.addProperty(prop);

      // And use it
      builder.useProperty(null, paramName);
    }

    // Use the current global meta-model to build the component in.
    MetaModel model = MetaModelManager.getInstance().getModel();
    IPropertyTypeSource propSource = model.getPropertyTypeSource();
    try
    {
      return (WidgetComponentType)builder.build(propSource);
    }
    catch (ValidationException ex)
    {
      logger.error(ex);
      return null;
    }
  }

  private static byte[] safeGetEncodedBytes(String text)
  {
    try
    {
      return text.getBytes(ENCODING);
    }
    catch(UnsupportedEncodingException ex)
    {
      // Never happens
      return null;
    }
  }
}