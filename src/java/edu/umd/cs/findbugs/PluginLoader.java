/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2003-2005 University of Maryland
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.WillClose;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.dom4j.Node;
import org.dom4j.io.SAXReader;

import edu.umd.cs.findbugs.ba.AnalysisContext;
import edu.umd.cs.findbugs.charsets.UTF8;
import edu.umd.cs.findbugs.classfile.IAnalysisEngineRegistrar;
import edu.umd.cs.findbugs.cloud.Cloud;
import edu.umd.cs.findbugs.cloud.CloudFactory;
import edu.umd.cs.findbugs.cloud.CloudPlugin;
import edu.umd.cs.findbugs.cloud.CloudPluginBuilder;
import edu.umd.cs.findbugs.cloud.username.NameLookup;
import edu.umd.cs.findbugs.io.IO;
import edu.umd.cs.findbugs.plan.ByInterfaceDetectorFactorySelector;
import edu.umd.cs.findbugs.plan.DetectorFactorySelector;
import edu.umd.cs.findbugs.plan.DetectorOrderingConstraint;
import edu.umd.cs.findbugs.plan.ReportingDetectorFactorySelector;
import edu.umd.cs.findbugs.plan.SingleDetectorFactorySelector;
import edu.umd.cs.findbugs.plugins.DuplicatePluginIdError;
import edu.umd.cs.findbugs.plugins.DuplicatePluginIdException;
import edu.umd.cs.findbugs.updates.UpdateChecker;
import edu.umd.cs.findbugs.util.ClassName;
import edu.umd.cs.findbugs.util.JavaWebStart;
import edu.umd.cs.findbugs.util.Util;

/**
 * Loader for a FindBugs plugin. A plugin is a jar file containing two metadata
 * files, "findbugs.xml" and "messages.xml". Those files specify
 * <ul>
 * <li>the bug pattern Detector classes,
 * <li>the bug patterns detected (including all text for displaying detected
 * instances of those patterns), and
 * <li>the "bug codes" which group together related bug instances
 * </ul>
 *
 * <p>
 * The PluginLoader creates a Plugin object to store the Detector factories and
 * metadata.
 * </p>
 *
 * @author David Hovemeyer
 * @see Plugin
 * @see PluginException
 */
public class PluginLoader {

    /**
     *
     */
    private static final String XPATH_PLUGIN_SHORT_DESCRIPTION = "/MessageCollection/Plugin/ShortDescription";
    private static final String XPATH_PLUGIN_WEBSITE = "/FindbugsPlugin/@website";
    private static final String XPATH_PLUGIN_PROVIDER = "/FindbugsPlugin/@provider";
    private static final String XPATH_PLUGIN_PLUGINID = "/FindbugsPlugin/@pluginid";

    private static final boolean DEBUG = SystemProperties.getBoolean("findbugs.debug.PluginLoader");

    // ClassLoader used to load classes and resources
    private final ClassLoader classLoader;

    private final ClassLoader classLoaderForResources;

    // Keep a count of how many plugins we've seen without a
    // "pluginid" attribute, so we can assign them all unique ids.
    private static int nextUnknownId;

    // The loaded Plugin
    private Plugin plugin;

    private final boolean corePlugin;

    boolean initialPlugin;

    boolean cannotDisable;

    private boolean optionalPlugin;


    private final URL loadedFrom;

    private final String jarName;

    private final URI loadedFromUri;

    static HashSet<String> loadedPluginIds = new HashSet<String>();
    static {
        if (DEBUG) {
            System.out.println("Debugging plugin loading. FindBugs version "
                    + Version.getReleaseWithDateIfDev());
        }
        loadInitialPlugins();
    }

    /**
     * Constructor.
     *
     * @param url
     *            the URL of the plugin Jar file
     * @throws PluginException
     *             if the plugin cannot be fully loaded
     */
    @Deprecated
    public PluginLoader(URL url) throws PluginException {
        this(url, toUri(url), null, false, true);
    }


    /**
     * Constructor.
     *
     * @param url
     *            the URL of the plugin Jar file
     * @param parent
     *            the parent classloader
     * @deprecated Use {@link #PluginLoader(URL,ClassLoader,boolean,boolean)} instead
     */
    @Deprecated
    public PluginLoader(URL url, ClassLoader parent) throws PluginException {
        this(url, toUri(url), parent, false, true);
    }


    /**
     * Constructor.
     *
     * @param url
     *            the URL of the plugin Jar file
     * @param uri
     * @param parent
     *            the parent classloader
     * @param isInitial
     *          is this plugin loaded from one of the standard locations for plugins
     * @param optional
     *          is this an optional plugin
     */
    private PluginLoader(@Nonnull URL url, URI uri, ClassLoader parent, boolean isInitial, boolean optional) throws PluginException {
        URL[] loaderURLs = createClassloaderUrls(url);
        classLoader = new URLClassLoader(loaderURLs, parent);
        classLoaderForResources = new URLClassLoader(loaderURLs);
        loadedFrom = url;
        loadedFromUri = uri;
        jarName = getJarName(url);
        corePlugin = false;
        initialPlugin = isInitial;
        optionalPlugin = optional;
        plugin = init();
        Plugin.putPlugin(loadedFromUri, plugin);
    }


    /**
     * Patch for issue 3429143: allow plugins load classes/resources from 3rd
     * party jars
     *
     * @param url
     *            plugin jar location as url
     * @return non empty list with url to be used for loading classes from given
     *         plugin. If plugin jar contains standard Java manifest file, all
     *         entries of its "Class-Path" attribute will be translated to the
     *         jar-relative url's and added to the returned list. If plugin jar
     *         does not contains a manifest, or manifest does not have
     *         "Class-Path" attribute, the given argument will be the only entry
     *         in the array.
     * @throws PluginException
     */
    private @Nonnull URL[] createClassloaderUrls(@Nonnull URL url) throws PluginException {
        List<URL> urls = new ArrayList<URL>();
        urls.add(url);

        JarInputStream jis = null;
        try {
            jis = new JarInputStream(url.openStream());
            Manifest mf = jis.getManifest();
            if(mf == null) {
                return urls.toArray(new URL[urls.size()]);
            }
            Attributes atts = mf.getMainAttributes();
            if (atts != null) {
                String classPath = atts.getValue(Attributes.Name.CLASS_PATH);
                if (classPath != null) {
                    String jarRoot = url.toString();
                    jarRoot = jarRoot.substring(0, jarRoot.lastIndexOf("/") + 1);
                    String[] jars = classPath.split(",");
                    for (String jar : jars) {
                        jar = jarRoot + jar.trim();
                        urls.add(new URL(jar));
                    }
                }
            }
        } catch (IOException ioe) {
           throw new PluginException("Failed loading manifest for plugin jar: " + url, ioe);
        } finally {
            if (jis != null) {
                try {
                    jis.close();
                } catch (IOException ioe) {
                }
            }
        }

        return urls.toArray(new URL[urls.size()]);
    }

    /**
     * Constructor. Loads a plugin using the caller's class loader. This
     * constructor should only be used to load the "core" findbugs detectors,
     * which are built into findbugs.jar.
     * @throws PluginException
     */
    @Deprecated
    public PluginLoader() throws PluginException {
        classLoader = getClass().getClassLoader();
        classLoaderForResources = classLoader;
        corePlugin = true;
        initialPlugin = true;
        optionalPlugin = false;

        loadedFrom = computeCoreUrl();
        try {
            loadedFromUri = loadedFrom.toURI();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Failed to parse uri: " + loadedFrom);
        }
        jarName = getJarName(loadedFrom);
        plugin = init();
        Plugin.putPlugin(null, plugin);
    }

    /**
     * Constructor. Loads a plugin using the caller's class loader. This
     * constructor should only be used to load the "core" findbugs detectors,
     * which are built into findbugs.jar.
     * @throws PluginException
     */
    @Deprecated
    public PluginLoader(boolean fake, URL url) throws PluginException {
        classLoader = getClass().getClassLoader();
        classLoaderForResources = classLoader;
        corePlugin = false;
        initialPlugin = true;
        optionalPlugin = false;

        loadedFrom = url;
        try {
            loadedFromUri = loadedFrom.toURI();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Failed to parse uri: " + loadedFrom);
        }
        jarName = getJarName(loadedFrom);
    }

    private URL computeCoreUrl() {
        URL from;
        String findBugsClassFile = ClassName.toSlashedClassName(FindBugs.class) + ".class";
        URL me = FindBugs.class.getClassLoader().getResource(findBugsClassFile);
        if (DEBUG)
            System.out.println("FindBugs.class loaded from " + me);
        if(me == null) {
            throw new IllegalStateException("Failed to load " + findBugsClassFile);
        }
        try {
            String u = me.toString();
            if (u.startsWith("jar:") && u.endsWith("!/" + findBugsClassFile)) {
                u = u.substring(4, u.indexOf("!/"));

                from = new URL(u);

            } else if (u.endsWith(findBugsClassFile)) {
                u = u.substring(0, u.indexOf(findBugsClassFile));
                from = new URL(u);
            } else {
                throw new IllegalArgumentException("Unknown url shema: " + u);
            }

        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Failed to parse url: " + me);
        }
        if (DEBUG)
            System.out.println("Core class files loaded from " + from);
        return from;
    }

    public URL getURL() {
        return loadedFrom;
    }

    public URI getURI() {
        return loadedFromUri;
    }

    private static URI toUri(URL url) throws PluginException {
        try {
            return url.toURI();
        } catch (URISyntaxException e) {
            throw new PluginException("Bad uri: " + url, e);
        }
    }

    /**
     * @param url
     * @return
     */
    private String getJarName(URL url) {
        String location = url.getPath();
        int i = location.lastIndexOf("/");
        location = location.substring(i + 1);
        return location;
    }

    /**
     * @return Returns the classLoader.
     */
    public ClassLoader getClassLoader() {
        return classLoader;
    }

    /**
     * Get the Plugin.
     *
     * @throws PluginException
     *             if the plugin cannot be fully loaded
     */
    public Plugin loadPlugin() throws PluginException {
        return getPlugin();
    }

    /**
     * Get the Plugin.
     *
     */
    public Plugin getPlugin()  {
        if (plugin == null)
            throw new AssertionError("plugin not already loaded");

        return plugin;
    }

    private static URL resourceFromPlugin(URL u, String args) throws MalformedURLException {
        String path = u.getPath();
        if (path.endsWith(".zip") || path.endsWith(".jar")) {
            return new URL("jar:" + u.toString() + "!/" + args);
        } else if (path.endsWith("/")) {
            return new URL(u.toString() + args);
        } else {
            return new URL(u.toString() + "/" + args);

        }
    }

    /**
     * Get a resource using the URLClassLoader classLoader. We try findResource
     * first because (based on experiment) we can trust it to prefer resources
     * in the jarfile to resources on the filesystem. Simply calling
     * classLoader.getResource() allows the filesystem to override the jarfile,
     * which can mess things up if, for example, there is a findbugs.xml or
     * messages.xml in the current directory.
     *
     * @param name
     *            resource to get
     * @return URL for the resource, or null if it could not be found
     */
    public URL getResource(String name) {
        if (isCorePlugin()) {
            URL url = getCoreResource(name);
            if (url != null &&  IO.verifyURL(url))
                return url;
        }
        if (loadedFrom != null) {
            try {
                URL url = resourceFromPlugin(loadedFrom, name);
                if (DEBUG) {
                    System.out.println("Trying to load " + name + " from " + url);
                }
                if (IO.verifyURL(url))
                    return url;
            } catch (MalformedURLException e) {
                assert true;
            }

        }

        if (classLoaderForResources instanceof URLClassLoader) {

            URLClassLoader urlClassLoader = (URLClassLoader) classLoaderForResources;
            if (DEBUG) {
                System.out.println("Trying to load " + name + " using URLClassLoader.findResource");
                System.out.println("  from urls: " + Arrays.asList(urlClassLoader.getURLs()));
            }
            URL url = urlClassLoader.findResource(name);
            if (url == null)
                url = urlClassLoader.findResource("/" + name);
            if (IO.verifyURL(url))
                return url;
        }

        if (DEBUG) {
            System.out.println("Trying to load " + name + " using ClassLoader.getResource");
        }
        URL url = classLoaderForResources.getResource(name);
        if (url == null)
            url = classLoaderForResources.getResource("/" + name);
        if (IO.verifyURL(url))
            return url;

        return null;
    }

    static @CheckForNull
    URL getCoreResource(String name) {
        URL u = loadFromFindBugsPluginDir(name);
        if (u != null)
            return u;
        u = loadFromFindBugsEtcDir(name);
        if (u != null)
            return u;
        u = PluginLoader.class.getResource(name);
        if (u != null)
            return u;
        u = PluginLoader.class.getResource("/"+name);

        return u;
    }

    public static @CheckForNull
    URL loadFromFindBugsEtcDir(String name) {

        String findBugsHome = DetectorFactoryCollection.getFindBugsHome();
        if (findBugsHome != null) {
            File f = new File(new File(new File(findBugsHome), "etc"), name);
            if (f.canRead())
                try {
                    return f.toURL();
                } catch (MalformedURLException e) {
                    // ignore it
                }
        }

        return null;
    }

    public static @CheckForNull
    URL loadFromFindBugsPluginDir(String name) {

        String findBugsHome = DetectorFactoryCollection.getFindBugsHome();
        if (findBugsHome != null) {
            File f = new File(new File(new File(findBugsHome), "plugin"), name);
            if (f.canRead())
                try {
                    return f.toURI().toURL();
                } catch (MalformedURLException e) {
                    // ignore it
                }
        }

        return null;
    }

    private static <T> Class<? extends T> getClass(ClassLoader loader, String className, Class<T> type) throws PluginException {
        try {
            return loader.loadClass(className).asSubclass(type);
        } catch (ClassNotFoundException e) {
            throw new PluginException("Unable to load " + className, e);
        } catch (ClassCastException e) {
            throw new PluginException("Cannot cast " + className + " to " + type.getName(), e);
        }

    }

    @SuppressWarnings("unchecked")
    private Plugin init() throws PluginException {

        if (DEBUG)
            System.out.println("Loading plugin from " + loadedFrom);
        // Plugin descriptor (a.k.a, "findbugs.xml"). Defines
        // the bug detectors and bug patterns that the plugin provides.
        Document pluginDescriptor;

        // Unique plugin id
        String pluginId;


        // Read the plugin descriptor
        String name = "findbugs.xml";
        URL findbugsXML_URL = getResource(name);
        if (findbugsXML_URL == null)
            throw new PluginException("Couldn't find \"" + name + "\" in plugin");
        if (DEBUG)
            System.out.println("PluginLoader found " + name + " at: " + findbugsXML_URL);

        if (jarName != null && !findbugsXML_URL.toString().contains(jarName)
                && !(corePlugin && findbugsXML_URL.toString().endsWith("etc/findbugs.xml"))) {
            String classloaderName = classLoader.getClass().getName();
            if (classLoader instanceof URLClassLoader) {
                classloaderName += Arrays.asList(((URLClassLoader) classLoader).getURLs());
            }
            throw new PluginDoesntContainMetadataException((corePlugin ? "Core plugin" : "Plugin ") + jarName
                    + " doesn't contain findbugs.xml; got " + findbugsXML_URL + " from " + classloaderName);
        }
        SAXReader reader = new SAXReader();

        try {
            Reader r = UTF8.bufferedReader(findbugsXML_URL.openStream());
            pluginDescriptor = reader.read(r);
        } catch (DocumentException e) {
            throw new PluginException("Couldn't parse \"" + findbugsXML_URL + "\" using " + reader.getClass().getName(), e);
        } catch (IOException e) {
            throw new PluginException("Couldn't open \"" + findbugsXML_URL + "\"", e);

        }

        // Get the unique plugin id (or generate one, if none is present)
        pluginId = pluginDescriptor.valueOf(XPATH_PLUGIN_PLUGINID);
        if (pluginId.equals("")) {
            synchronized (PluginLoader.class) {
                pluginId = "plugin" + nextUnknownId++;
            }
        }
        cannotDisable = Boolean.parseBoolean(pluginDescriptor.valueOf("/FindbugsPlugin/@cannotDisable"));

        String de = pluginDescriptor.valueOf("/FindbugsPlugin/@defaultenabled");
        if (de != null && de.toLowerCase().trim().equals("false")) {
            optionalPlugin = true;
        }
        if (optionalPlugin)
            cannotDisable = false;
        if (!loadedPluginIds.add(pluginId)) {
            Plugin existingPlugin = Plugin.getByPluginId(pluginId);
            URL u = existingPlugin == null ? null : existingPlugin.getPluginLoader().getURL();
            if (cannotDisable && initialPlugin)
                throw new DuplicatePluginIdError(pluginId, loadedFrom, u);
            else
                throw new DuplicatePluginIdException(pluginId, loadedFrom, u);
        }

        String version = pluginDescriptor.valueOf("/FindbugsPlugin/@version");
        String releaseDate = pluginDescriptor.valueOf("/FindbugsPlugin/@releaseDate");
        List<Document> messageCollectionList = getMessageDocuments();

        if ((releaseDate == null  || releaseDate.length() == 0) && isCorePlugin())
            releaseDate = Version.CORE_PLUGIN_RELEASE_DATE;
        // Create the Plugin object (but don't assign to the plugin field yet,
        // since we're still not sure if everything will load correctly)
        Date parsedDate = parseDate(releaseDate);
        Plugin plugin = new Plugin(pluginId, version, parsedDate, this, !optionalPlugin, cannotDisable);

        // Set provider and website, if specified
        String provider = pluginDescriptor.valueOf(XPATH_PLUGIN_PROVIDER).trim();
        if (!provider.equals(""))
            plugin.setProvider(provider);
        String website = pluginDescriptor.valueOf(XPATH_PLUGIN_WEBSITE).trim();
        if (!website.equals(""))
            try {
                plugin.setWebsite(website);
            } catch (URISyntaxException e1) {
                AnalysisContext.logError("Plugin " + pluginId + " has invalid website: " + website, e1);
            }

        String updateUrl = pluginDescriptor.valueOf("/FindbugsPlugin/@update-url").trim();
        if (!updateUrl.equals(""))
            try {
                plugin.setUpdateUrl(updateUrl);
            } catch (URISyntaxException e1) {
                AnalysisContext.logError("Plugin " + pluginId + " has invalid update check URL: " + website, e1);

            }


        // Set short description, if specified
        Node pluginShortDesc = null;
        try {
            pluginShortDesc = findMessageNode(messageCollectionList, XPATH_PLUGIN_SHORT_DESCRIPTION,
                    "no plugin description");
        } catch (PluginException e) {
            // Missing description is not fatal, so ignore
        }
        if (pluginShortDesc != null) {
            plugin.setShortDescription(pluginShortDesc.getText().trim());
        }
        Node detailedDescription = null;
        try {
            detailedDescription = findMessageNode(messageCollectionList, "/MessageCollection/Plugin/Details",
                    "no plugin description");
        } catch (PluginException e) {
            // Missing description is not fatal, so ignore
        }
        if (detailedDescription != null) {
            plugin.setDetailedDescription(detailedDescription.getText().trim());
        }
        List<Node> globalOptionNodes = pluginDescriptor.selectNodes("/FindbugsPlugin/GlobalOptions/Property");
        for(Node optionNode : globalOptionNodes) {
            String key = optionNode.valueOf("@key");
            String value = optionNode.getText().trim();
            plugin.setMyGlobalOption(key, value);
        }

        List<Node> cloudNodeList = pluginDescriptor.selectNodes("/FindbugsPlugin/Cloud");
        for (Node cloudNode : cloudNodeList) {

            String cloudClassname = cloudNode.valueOf("@cloudClass");
            String cloudId = cloudNode.valueOf("@id");
            String usernameClassname = cloudNode.valueOf("@usernameClass");
            boolean onlineStorage = Boolean.valueOf(cloudNode.valueOf("@onlineStorage"));
            String propertiesLocation = cloudNode.valueOf("@properties");
            boolean disabled = Boolean.valueOf(cloudNode.valueOf("@disabled")) && !cloudId.equals(CloudFactory.DEFAULT_CLOUD);
            if (disabled)
                continue;
            boolean hidden = Boolean.valueOf(cloudNode.valueOf("@hidden")) && !cloudId.equals(CloudFactory.DEFAULT_CLOUD);

            Class<? extends Cloud> cloudClass = getClass(classLoader, cloudClassname, Cloud.class);

            Class<? extends NameLookup> usernameClass = getClass(classLoader, usernameClassname, NameLookup.class);
            Node cloudMessageNode = findMessageNode(messageCollectionList, "/MessageCollection/Cloud[@id='" + cloudId + "']",
                    "Missing Cloud description for cloud " + cloudId);
            String description = getChildText(cloudMessageNode, "Description").trim();
            String details = getChildText(cloudMessageNode, "Details").trim();
            PropertyBundle properties = new PropertyBundle();
            if (propertiesLocation != null && propertiesLocation.length() > 0) {
                URL properiesURL = classLoader.getResource(propertiesLocation);
                if (properiesURL == null)
                    continue;
                properties.loadPropertiesFromURL(properiesURL);
            }
            List<Node> propertyNodes = cloudNode.selectNodes("Property");
            for (Node node : propertyNodes) {
                String key = node.valueOf("@key");
                String value = node.getText().trim();
                properties.setProperty(key, value);
            }


            CloudPlugin cloudPlugin = new CloudPluginBuilder().setFindbugsPluginId(pluginId).setCloudid(cloudId).setClassLoader(classLoader)
                    .setCloudClass(cloudClass).setUsernameClass(usernameClass).setHidden(hidden).setProperties(properties)
                    .setDescription(description).setDetails(details).setOnlineStorage(onlineStorage).createCloudPlugin();
            plugin.addCloudPlugin(cloudPlugin);


        }

        // Create PluginComponents
        try {

            List<Node> componentNodeList = pluginDescriptor.selectNodes("/FindbugsPlugin/PluginComponent");
            for (Node componentNode : componentNodeList) {
                String componentKindname = componentNode.valueOf("@componentKind");
                if (componentKindname == null) throw new PluginException("Missing @componentKind for " + pluginId
                        + " loaded from " + loadedFrom);
                String componentClassname = componentNode.valueOf("@componentClass");
                if (componentClassname == null) throw new PluginException("Missing @componentClassname for " + pluginId
                        + " loaded from " + loadedFrom);
                String componentId = componentNode.valueOf("@id");
                if (componentId == null) throw new PluginException("Missing @id for " + pluginId
                        + " loaded from " + loadedFrom);

                try {
                    String propertiesLocation = componentNode.valueOf("@properties");
                    boolean disabled = Boolean.valueOf(componentNode.valueOf("@disabled"));

                    Node filterMessageNode = findMessageNode(messageCollectionList,
                            "/MessageCollection/PluginComponent[@id='" + componentId + "']",
                            "Missing Cloud description for PluginComponent " + componentId);
                    String description = getChildText(filterMessageNode, "Description").trim();
                    String details = getChildText(filterMessageNode, "Details").trim();
                    PropertyBundle properties = new PropertyBundle();
                    if (propertiesLocation != null && propertiesLocation.length() > 0) {
                        URL properiesURL = classLoaderForResources.getResource(propertiesLocation);
                        if (properiesURL == null) {
                            AnalysisContext.logError("Could not load properties for " + pluginId + " component " + componentId
                                    + " from " + propertiesLocation);
                            continue;
                        }
                        properties.loadPropertiesFromURL(properiesURL);
                    }
                    List<Node> propertyNodes = componentNode.selectNodes("Property");
                    for (Node node : propertyNodes) {
                        String key = node.valueOf("@key");
                        String value = node.getText();
                        properties.setProperty(key, value);
                    }

                    Class<?> componentKind =  classLoader.loadClass(componentKindname);

                    loadComponentPlugin(plugin, componentKind, componentClassname, componentId, disabled, description, details,
                            properties);

                } catch (RuntimeException e) {
                    AnalysisContext.logError("Unable to load ComponentPlugin " + componentId +
                            " : " + componentClassname + " implementing " + componentKindname, e);
                }
            }

            // Create FindBugsMains


                List<Node> findBugsMainList = pluginDescriptor.selectNodes("/FindbugsPlugin/FindBugsMain");
                for (Node main : findBugsMainList) {
                    String className = main.valueOf("@class");
                    if (className == null) throw new PluginException("Missing @class for FindBugsMain in plugin" + pluginId
                            + " loaded from " + loadedFrom);
                    String cmd = main.valueOf("@cmd");
                    if (cmd == null) throw new PluginException("Missing @cmd for for FindBugsMain in plugin " + pluginId
                            + " loaded from " + loadedFrom);
                    String kind = main.valueOf("@kind");
                    boolean analysis = Boolean.valueOf(main.valueOf("@analysis"));
                    Element mainMessageNode = (Element) findMessageNode(messageCollectionList,
                            "/MessageCollection/FindBugsMain[@cmd='" + cmd
                            // + " and @class='" + className
                            +"']/Description",
                            "Missing FindBugsMain description for cmd " + cmd);
                    String description = mainMessageNode.getTextTrim();
                    try {

                        Class<?> mainClass =  classLoader.loadClass(className);
                        plugin.addFindBugsMain(mainClass, cmd, description, kind, analysis);

                    } catch (Exception e) {
                        AnalysisContext.logError("Unable to load FindBugsMain " + cmd +
                                " : " + className + " in plugin " + pluginId
                                + " loaded from " + loadedFrom, e);
                    }
                }

            List<Node> detectorNodeList = pluginDescriptor.selectNodes("/FindbugsPlugin/Detector");
            int detectorCount = 0;
            for (Node detectorNode : detectorNodeList) {
                String className = detectorNode.valueOf("@class");
                String speed = detectorNode.valueOf("@speed");
                String disabled = detectorNode.valueOf("@disabled");
                String reports = detectorNode.valueOf("@reports");
                String requireJRE = detectorNode.valueOf("@requirejre");
                String hidden = detectorNode.valueOf("@hidden");
                if (speed == null || speed.length() == 0)
                    speed = "fast";

                // System.out.println("Found detector: class="+className+", disabled="+disabled);

                // Create DetectorFactory for the detector
                Class<?> detectorClass = null;
                if (!FindBugs.noAnalysis) {
                    detectorClass = classLoader.loadClass(className);

                    if (!Detector.class.isAssignableFrom(detectorClass) && !Detector2.class.isAssignableFrom(detectorClass))
                        throw new PluginException("Class " + className + " does not implement Detector or Detector2");
                }
                DetectorFactory factory = new DetectorFactory(plugin, className, detectorClass, !disabled.equals("true"), speed,
                        reports, requireJRE);
                if (Boolean.valueOf(hidden).booleanValue())
                    factory.setHidden(true);
                factory.setPositionSpecifiedInPluginDescriptor(detectorCount++);
                plugin.addDetectorFactory(factory);

                // Find Detector node in one of the messages files,
                // to get the detail HTML.
                Node node = findMessageNode(messageCollectionList, "/MessageCollection/Detector[@class='" + className
                        + "']/Details", "Missing Detector description for detector " + className);

                Element details = (Element) node;
                String detailHTML = details.getText();
                StringBuilder buf = new StringBuilder();
                buf.append("<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.01 Transitional//EN\">\n");
                buf.append("<HTML><HEAD><TITLE>Detector Description</TITLE></HEAD><BODY>\n");
                buf.append(detailHTML);
                buf.append("</BODY></HTML>\n");
                factory.setDetailHTML(buf.toString());
            }
        } catch (ClassNotFoundException e) {
            throw new PluginException("Could not instantiate detector class: " + e, e);
        }

        // Create ordering constraints
        Node orderingConstraintsNode = pluginDescriptor.selectSingleNode("/FindbugsPlugin/OrderingConstraints");
        if (orderingConstraintsNode != null) {
            // Get inter-pass and intra-pass constraints
            for (Element constraintElement : (List<Element>) orderingConstraintsNode.selectNodes("./SplitPass|./WithinPass")) {
                // Create the selectors which determine which detectors are
                // involved in the constraint
                DetectorFactorySelector earlierSelector = getConstraintSelector(constraintElement, plugin, "Earlier");
                DetectorFactorySelector laterSelector = getConstraintSelector(constraintElement, plugin, "Later");

                // Create the constraint
                DetectorOrderingConstraint constraint = new DetectorOrderingConstraint(earlierSelector, laterSelector);

                // Keep track of which constraints are single-source
                constraint.setSingleSource(earlierSelector instanceof SingleDetectorFactorySelector);

                // Add the constraint to the plugin
                if (constraintElement.getName().equals("SplitPass"))
                    plugin.addInterPassOrderingConstraint(constraint);
                else
                    plugin.addIntraPassOrderingConstraint(constraint);
            }
        }

        // register global Category descriptions

        List<Node> categoryNodeListGlobal = pluginDescriptor.selectNodes("/FindbugsPlugin/BugCategory");
        for(Node categoryNode : categoryNodeListGlobal) {
            String key = categoryNode.valueOf("@category");
            if (key.equals(""))
                throw new PluginException("BugCategory element with missing category attribute");
            BugCategory bc = plugin.addOrCreateBugCategory(key);

            boolean hidden = Boolean.valueOf(categoryNode.valueOf("@hidden"));
            if (hidden)
                bc.setHidden(hidden);
        }


        for (Document messageCollection : messageCollectionList) {
            List<Node> categoryNodeList = messageCollection.selectNodes("/MessageCollection/BugCategory");
            if (DEBUG)
                System.out.println("found " + categoryNodeList.size() + " categories in " + pluginId);
            for (Node categoryNode : categoryNodeList) {
                String key = categoryNode.valueOf("@category");
                if (key.equals(""))
                    throw new PluginException("BugCategory element with missing category attribute");
                BugCategory bc = plugin.addOrCreateBugCategory(key);
                String shortDesc = getChildText(categoryNode, "Description");
                bc.setShortDescription(shortDesc);
                try {
                    String abbrev = getChildText(categoryNode, "Abbreviation");
                    if (bc.getAbbrev() == null) {
                        bc.setAbbrev(abbrev);
                        if (DEBUG)
                            System.out.println("category " + key + " abbrev -> " + abbrev);
                    } else if (DEBUG)
                        System.out.println("rejected abbrev '" + abbrev + "' for category " + key + ": " + bc.getAbbrev());
                } catch (PluginException pe) {
                    if (DEBUG)
                        System.out.println("missing Abbreviation for category " + key + "/" + shortDesc);
                    // do nothing else -- Abbreviation is required, but handle
                    // its omission gracefully
                }
                try {
                    String details = getChildText(categoryNode, "Details");
                    if (bc.getDetailText() == null) {
                        bc.setDetailText(details);
                        if (DEBUG)
                            System.out.println("category " + key + " details -> " + details);
                    } else if (DEBUG)
                        System.out.println("rejected details [" + details + "] for category " + key + ": [" + bc.getDetailText()
                                + ']');
                } catch (PluginException pe) {
                    // do nothing -- LongDescription is optional
                }

            }
        }

        // Create BugPatterns
        List<Node> bugPatternNodeList = pluginDescriptor.selectNodes("/FindbugsPlugin/BugPattern");
        for (Node bugPatternNode : bugPatternNodeList) {
            String type = bugPatternNode.valueOf("@type");
            String abbrev = bugPatternNode.valueOf("@abbrev");
            String category = bugPatternNode.valueOf("@category");
            String experimental = bugPatternNode.valueOf("@experimental");

            // Find the matching element in messages.xml (or translations)
            String query = "/MessageCollection/BugPattern[@type='" + type + "']";
            Node messageNode = findMessageNode(messageCollectionList, query, "messages.xml missing BugPattern element for type "
                    + type);

            String shortDesc = getChildText(messageNode, "ShortDescription");
            String longDesc = getChildText(messageNode, "LongDescription");
            String detailText = getChildText(messageNode, "Details");
            int cweid = 0;
            try {
                String cweString = bugPatternNode.valueOf("@cweid");
                if (cweString.length() > 0)
                    cweid = Integer.parseInt(cweString);
            } catch (RuntimeException e) {
                assert true; // ignore
            }

            BugPattern bugPattern = new BugPattern(type, abbrev, category, Boolean.valueOf(experimental).booleanValue(),
                    shortDesc, longDesc, detailText, cweid);

            try {
                String deprecatedStr = bugPatternNode.valueOf("@deprecated");
                boolean deprecated = deprecatedStr.length() > 0 && Boolean.valueOf(deprecatedStr).booleanValue();
                if (deprecated) {
                    bugPattern.setDeprecated(deprecated);
                }
            } catch (RuntimeException e) {
                assert true; // ignore
            }

            plugin.addBugPattern(bugPattern);

        }

        // Create BugCodes
        Set<String> definedBugCodes = new HashSet<String>();
        for (Document messageCollection : messageCollectionList) {
            List<Node> bugCodeNodeList = messageCollection.selectNodes("/MessageCollection/BugCode");
            for (Node bugCodeNode : bugCodeNodeList) {
                String abbrev = bugCodeNode.valueOf("@abbrev");
                if (abbrev.equals(""))
                    throw new PluginException("BugCode element with missing abbrev attribute");
                if (definedBugCodes.contains(abbrev))
                    continue;
                String description = bugCodeNode.getText();

                String query = "/FindbugsPlugin/BugCode[@abbrev='" + abbrev + "']";
                Node fbNode = pluginDescriptor.selectSingleNode(query);
                int cweid = 0;
                if (fbNode != null)
                    try {
                        cweid = Integer.parseInt(fbNode.valueOf("@cweid"));
                    } catch (RuntimeException e) {
                        assert true; // ignore
                    }
                BugCode bugCode = new BugCode(abbrev, description, cweid);
                plugin.addBugCode(bugCode);
                definedBugCodes.add(abbrev);
            }

        }

        // If an engine registrar is specified, make a note of its classname
        Node node = pluginDescriptor.selectSingleNode("/FindbugsPlugin/EngineRegistrar");
        if (node != null) {
            String engineClassName = node.valueOf("@class");
            if (engineClassName == null) {
                throw new PluginException("EngineRegistrar element with missing class attribute");
            }

            try {
                Class<?> engineRegistrarClass = classLoader.loadClass(engineClassName);
                if (!IAnalysisEngineRegistrar.class.isAssignableFrom(engineRegistrarClass)) {
                    throw new PluginException(engineRegistrarClass + " does not implement IAnalysisEngineRegistrar");
                }

                plugin.setEngineRegistrarClass(engineRegistrarClass
                        .<IAnalysisEngineRegistrar> asSubclass(IAnalysisEngineRegistrar.class));
            } catch (ClassNotFoundException e) {
                throw new PluginException("Could not instantiate analysis engine registrar class: " + e, e);
            }

        }
        try {
            URL bugRankURL = getResource(BugRanker.FILENAME);

            if (bugRankURL == null) {
                // see
                // https://sourceforge.net/tracker/?func=detail&aid=2816102&group_id=96405&atid=614693
                // plugin can not have bugrank.txt. In this case, an empty
                // bugranker will be created
                if (DEBUG)
                    System.out.println("No " + BugRanker.FILENAME + " for plugin " + pluginId);
            }
            BugRanker ranker = new BugRanker(bugRankURL);
            plugin.setBugRanker(ranker);
        } catch (IOException e) {
            throw new PluginException("Couldn't parse \"" + BugRanker.FILENAME + "\"", e);
        }

        // Success!
        if (DEBUG)
            System.out.println("Loaded " + plugin.getPluginId() + " from " + loadedFrom);
        return plugin;
    }


    private  static  List<String> getPotentialMessageFiles() {
        // Load the message collections
        Locale locale = Locale.getDefault();
        String language = locale.getLanguage();
        String country = locale.getCountry();

        List<String> potential = new ArrayList<String>(3);
        if (country != null)
            potential.add("messages_" + language + "_" + country + ".xml");
        potential.add("messages_" + language + ".xml");
        potential.add("messages.xml");
        return potential;
    }

    private List<Document> getMessageDocuments() throws PluginException {
        // List of message translation files in decreasing order of precedence
        ArrayList<Document> messageCollectionList = new ArrayList<Document>();
        PluginException caught = null;
        for (String m : getPotentialMessageFiles())
            try {
                addCollection(messageCollectionList, m);
            } catch (PluginException e) {
                caught = e;
                AnalysisContext.logError(
                        "Error loading localized message file:" + m, e);
            }
        if (messageCollectionList.isEmpty()) {
            if (caught != null) throw caught;
            throw new PluginException("No message.xml files found");
        }
        return messageCollectionList;
    }


    private <T> void loadComponentPlugin(@SuppressWarnings("hiding") Plugin plugin,
            Class<T> componentKind, String componentClassname, String filterId,
            boolean disabled, String description, String details, PropertyBundle properties) throws PluginException {
        {
        Class<? extends T> componentClass = null;
        if (!FindBugs.noAnalysis) {
            componentClass = getClass(classLoader, componentClassname, componentKind);
        }

        ComponentPlugin<T> componentPlugin = new ComponentPlugin<T>(plugin, filterId, classLoader, componentClass,
                properties, !disabled, description, details);
        plugin.addComponentPlugin(componentKind, componentPlugin);
        }
    }

    private Date parseDate(String releaseDate) {
        if (releaseDate == null || releaseDate.length() == 0)
            return null;
        try {
            SimpleDateFormat releaseDateFormat = new SimpleDateFormat(UpdateChecker.PLUGIN_RELEASE_DATE);
            Date result = releaseDateFormat.parse(releaseDate);
            return result;
        } catch (ParseException e) {
            AnalysisContext.logError("unable to parse date " + releaseDate, e);
            return null;
        }
    }

    private static DetectorFactorySelector getConstraintSelector(Element constraintElement, Plugin plugin,
            String singleDetectorElementName/*
             * , String
             * detectorCategoryElementName
             */) throws PluginException {
        Node node = constraintElement.selectSingleNode("./" + singleDetectorElementName);
        if (node != null) {
            String detectorClass = node.valueOf("@class");
            return new SingleDetectorFactorySelector(plugin, detectorClass);
        }

        node = constraintElement.selectSingleNode("./" + singleDetectorElementName + "Category");
        if (node != null) {
            boolean spanPlugins = Boolean.valueOf(node.valueOf("@spanplugins")).booleanValue();

            String categoryName = node.valueOf("@name");
            if (!categoryName.equals("")) {
                if (categoryName.equals("reporting")) {
                    return new ReportingDetectorFactorySelector(spanPlugins ? null : plugin);
                } else if (categoryName.equals("training")) {
                    return new ByInterfaceDetectorFactorySelector(spanPlugins ? null : plugin, TrainingDetector.class);
                } else if (categoryName.equals("interprocedural")) {
                    return new ByInterfaceDetectorFactorySelector(spanPlugins ? null : plugin,
                            InterproceduralFirstPassDetector.class);
                } else {
                    throw new PluginException("Invalid category name " + categoryName + " in constraint selector node");
                }
            }
        }

        node = constraintElement.selectSingleNode("./" + singleDetectorElementName + "Subtypes");
        if (node != null) {
            boolean spanPlugins = Boolean.valueOf(node.valueOf("@spanplugins")).booleanValue();

            String superName = node.valueOf("@super");
            if (!superName.equals("")) {
                try {
                    Class<?> superClass = Class.forName(superName);
                    return new ByInterfaceDetectorFactorySelector(spanPlugins ? null : plugin, superClass);
                } catch (ClassNotFoundException e) {
                    throw new PluginException("Unknown class " + superName + " in constraint selector node");
                }
            }
        }
        throw new PluginException("Invalid constraint selector node");
    }

    private void addCollection(List<Document> messageCollectionList, String filename) throws PluginException {
        URL messageURL = getResource(filename);
        if (messageURL != null) {
            SAXReader reader = new SAXReader();
            try {
                Reader stream = UTF8.bufferedReader(messageURL.openStream());
                Document messageCollection;
                try {
                    messageCollection = reader.read(stream);
                } finally {
                    stream.close();
                }
                messageCollectionList.add(messageCollection);
            } catch (Exception e) {
                throw new PluginException("Couldn't parse \"" + messageURL + "\"", e);
            } finally {
            }

        }
    }

    private static Node findMessageNode(List<Document> messageCollectionList, String xpath, String missingMsg)
            throws PluginException {

        for (Document document : messageCollectionList) {
            Node node = document.selectSingleNode(xpath);
            if (node != null)
                return node;
        }
        throw new PluginException(missingMsg);
    }
    private static  String findMessageText(List<Document> messageCollectionList, String xpath, String missingMsg)
             {

        for (Document document : messageCollectionList) {
            Node node = document.selectSingleNode(xpath);
            if (node != null) {
                return node.getText().trim();
            }
        }
        return missingMsg;
    }


    private static String getChildText(Node node, String childName) throws PluginException {
        Node child = node.selectSingleNode(childName);
        if (child == null)
            throw new PluginException("Could not find child \"" + childName + "\" for node");
        return child.getText();
    }


    public static PluginLoader getPluginLoader(URL url, ClassLoader parent, boolean isInitial, boolean optional) throws PluginException {
        URI uri = toUri(url);
        Plugin plugin = Plugin.getPlugin(uri);
        if (plugin != null) {
            PluginLoader loader = plugin.getPluginLoader();

            assert loader.getClassLoader().getParent().equals(parent);
            return loader;
        }
        return new PluginLoader(url, uri, parent, isInitial, optional);
    }

    @Nonnull
    public static synchronized PluginLoader getCorePluginLoader() {
        Plugin plugin = Plugin.getPlugin(null);
        if (plugin != null) {
            return plugin.getPluginLoader();
        }
        throw new IllegalStateException("Core plugin not loaded yet!");
    }



    public boolean isCorePlugin() {
        return corePlugin;
    }



    static void installStandardPlugins() {
        String homeDir = DetectorFactoryCollection.getFindBugsHome();
        if (homeDir == null)
            return;
        File home = new File(homeDir);
        loadPlugins(home);
    }


    private static void loadPlugins(File home) {
        if (home.canRead() && home.isDirectory()) {
            loadPluginsInDir(new File(home, "plugin"), false);
            loadPluginsInDir(new File(home, "optionalPlugin"), true);
        }
    }

    static void installUserInstalledPlugins() {
        String homeDir = System.getProperty("user.home");
        if (homeDir == null)
            return;
        File homeFindBugs = new File(new File(homeDir), ".findbugs");
        loadPlugins(homeFindBugs);
    }

    private static void loadPluginsInDir(File pluginDir, boolean optional) {
        File[] contentList = pluginDir.listFiles();
        if (contentList == null) {
            return;
        }

        for (File file : contentList) {
            if (file.getName().endsWith(".jar")) {

                try {
                    URL url = file.toURI().toURL();
                    if (IO.verifyURL(url)) {
                        loadInitialPlugin(url, true, optional);
                        if (FindBugs.DEBUG)
                            System.out.println("Found plugin: " + file.toString());
                    }
                } catch (MalformedURLException e) {

                }

            }
        }

    }

    static synchronized void loadInitialPlugins() {
        loadCorePlugin();
        if (JavaWebStart.isRunningViaJavaWebstart()) {
            installWebStartPlugins();
        } else {
            installStandardPlugins();
            installUserInstalledPlugins();
        }
        Set<Entry<Object, Object>> entrySet = SystemProperties.getAllProperties().entrySet();
        for (Map.Entry<?, ?> e : entrySet) {
            if (e.getKey() instanceof String && e.getValue() instanceof String
                    && ((String) e.getKey()).startsWith("findbugs.plugin.")) {
                try {
                    String value = (String) e.getValue();
                    if (value.startsWith("file:") && !value.endsWith(".jar") && !value.endsWith("/"))
                        value += "/";
                    URL url = JavaWebStart.resolveRelativeToJnlpCodebase(value);
                    System.out.println("Loading " + e.getKey() + " from " + url);
                    loadInitialPlugin(url, true, false);
                } catch (MalformedURLException e1) {
                    AnalysisContext.logError(String.format("Bad URL for plugin: %s=%s", e.getKey(), e.getValue()), e1);
                }

            }
        }

        if (Plugin.getAllPlugins().size() > 1 && JavaWebStart.isRunningViaJavaWebstart()) {
            // disable security manager; plugins cause problems
            // http://lopica.sourceforge.net/faq.html
            // URL policyUrl =
            // Thread.currentThread().getContextClassLoader().getResource("my.java.policy");
            // Policy.getPolicy().refresh();
            try {
                System.setSecurityManager(null);
            } catch (Throwable e) {
                assert true; // keep going
            }
        }
    }

    private static void loadCorePlugin() {
        try {
            Plugin plugin = Plugin.getPlugin(null);
            if (plugin != null) {
                throw new IllegalStateException("Already loaded");
            }
            PluginLoader pluginLoader = new PluginLoader();
            plugin = pluginLoader.getPlugin();
            Plugin.putPlugin(null, plugin);
        } catch (PluginException e1) {
            throw new IllegalStateException("Unable to load core plugin", e1);
        }
    }

    private static void loadInitialPlugin(URL u, boolean initial, boolean optional) {
        try {
            getPluginLoader(u, PluginLoader.class.getClassLoader(), initial, optional);
        } catch (DuplicatePluginIdException ignored) {
            assert true;
        }catch (PluginException e) {
            AnalysisContext.logError("Unable to load plugin from " + u, e);
        }
    }

    /**
     * @param plugins
     */
    static void installWebStartPlugins() {
        URL pluginListProperties = getCoreResource("pluginlist.properties");
        BufferedReader in = null;
        if (pluginListProperties != null) {
            try {

                DetectorFactoryCollection.jawsDebugMessage(pluginListProperties.toString());
                URL base = getUrlBase(pluginListProperties);

                in = UTF8.bufferedReader(pluginListProperties.openStream());
                while (true) {
                    String plugin = in.readLine();

                    if (plugin == null)
                        break;
                    URL url = new URL(base, plugin);
                    try {
                        URLConnection connection = url.openConnection();
                        String contentType = connection.getContentType();
                        DetectorFactoryCollection.jawsDebugMessage("contentType : " + contentType);
                        if (connection instanceof HttpURLConnection)
                            ((HttpURLConnection) connection).disconnect();
                        loadInitialPlugin(url, true, false);
                    } catch (Exception e) {
                        DetectorFactoryCollection.jawsDebugMessage("error loading " + url + " : " + e.getMessage());

                    }

                }
            } catch (Exception e) {
                DetectorFactoryCollection.jawsDebugMessage("error : " + e.getMessage());
            } finally {
                Util.closeSilently(in);
            }

        }
    }

    /**
     * @param pluginListProperties
     * @return
     * @throws MalformedURLException
     */
    private static URL getUrlBase(URL pluginListProperties) throws MalformedURLException {
        String urlname = pluginListProperties.toString();
        URL base = pluginListProperties;
        int pos = urlname.indexOf("!/");
        if (pos >= 0 && urlname.startsWith("jar:")) {
            urlname = urlname.substring(4, pos);
            base = new URL(urlname);
        }
        return base;
    }

    @Override
    public String toString() {
        if (plugin == null)
            return String.format("PluginLoader(%s)", loadedFrom);
        return String.format("PluginLoader(%s, %s)", plugin.getPluginId(), loadedFrom);
    }

    static public class Summary {
        public final String id;
        public final String description;
        public final String provider;
        public final String webbsite;

        public Summary(String id, String description, String provider,
                String website) {
            super();
            this.id = id;
            this.description = description;
            this.provider = provider;
            this.webbsite = website;
        }
    }

    public static Summary validate(File file) throws IllegalArgumentException {
        String path = file.getPath();
        if (!file.getName().endsWith(".jar")) {
            String message = "File " + path + " is not a .jar file";
            throw new IllegalArgumentException(message);
        }
        if (!file.isFile() || !file.canRead()) {
            String message = "File " + path
                    + " is not a file or is not readable";
            throw new IllegalArgumentException(message);
        }
        if (file.length() == 0) {
            String message = "File " + path + " is empty";
            throw new IllegalArgumentException(message);
        }

        ZipFile zip = null;
        try {
            zip = new ZipFile(file);
            ZipEntry findbugsXML = zip.getEntry("findbugs.xml");
            if (findbugsXML == null) {
                throw new IllegalArgumentException(
                        "plugin doesn't contain a findbugs.xml file");
            }
            ZipEntry messagesXML = zip.getEntry("messages.xml");
            if (messagesXML == null) {
                throw new IllegalArgumentException(
                        "plugin doesn't contain a messages.xml file");
            }
            Document pluginDocument = parseDocument(zip.getInputStream(findbugsXML));
            String pluginId = pluginDocument.valueOf(XPATH_PLUGIN_PLUGINID).trim();
            String provider = pluginDocument.valueOf(XPATH_PLUGIN_PROVIDER).trim();
            String website = pluginDocument.valueOf(XPATH_PLUGIN_WEBSITE).trim();
            List<Document> msgDocuments = new ArrayList<Document>(3);
            for (String msgFile : getPotentialMessageFiles()) {
                ZipEntry msgEntry = zip.getEntry(msgFile);
                if (msgEntry == null) {
                    continue;
                }
                Document msgDocument = parseDocument(zip.getInputStream(msgEntry));
                msgDocuments.add(msgDocument);
            }
            String shortDesc = findMessageText(msgDocuments,
                    XPATH_PLUGIN_SHORT_DESCRIPTION, "");
            return new Summary(pluginId, shortDesc, provider, website);
        } catch (DocumentException e) {
            throw new IllegalArgumentException(e);
        } catch (IOException e) {
            throw new IllegalArgumentException(e);
        } finally {
            Util.closeSilently(zip);
        }
    }


    private static Document parseDocument(@WillClose InputStream in) throws DocumentException {
        Reader r = UTF8.bufferedReader(in);
        try {

            SAXReader reader = new SAXReader();

            Document d = reader.read(r);

            return d;
        } finally {
            Util.closeSilently(r);
        }
    }
}

