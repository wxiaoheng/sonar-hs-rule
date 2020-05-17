/*
 * SonarQube PMD Plugin
 * Copyright (C) 2012-2019 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.pmd;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.Properties;

import org.sonar.api.batch.ScannerSide;
import org.sonar.api.batch.fs.FilePredicates;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.fs.InputFile.Type;
import org.sonar.api.batch.rule.ActiveRules;
import org.sonar.api.config.Configuration;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.api.utils.log.Profiler;
import org.sonar.plugins.java.api.JavaResourceLocator;
import org.sonar.plugins.pmd.xml.PmdRuleSet;
import org.sonar.plugins.pmd.xml.PmdRuleSets;

import net.sourceforge.pmd.PMDVersion;
import net.sourceforge.pmd.Report;
import net.sourceforge.pmd.RuleContext;
import net.sourceforge.pmd.RuleSet;
import net.sourceforge.pmd.RuleSetFactory;
import net.sourceforge.pmd.RuleSetNotFoundException;
import net.sourceforge.pmd.RuleSets;

@ScannerSide
public class PmdExecutor {

    private static final Logger LOGGER = Loggers.get(PmdExecutor.class);

    private final FileSystem fs;
    private final ActiveRules rulesProfile;
    private final PmdConfiguration pmdConfiguration;
    private final JavaResourceLocator javaResourceLocator;
    private final Configuration settings;

    public PmdExecutor(FileSystem fileSystem, ActiveRules rulesProfile,
                       PmdConfiguration pmdConfiguration, JavaResourceLocator javaResourceLocator, Configuration settings) {
        this.fs = fileSystem;
        this.rulesProfile = rulesProfile;
        this.pmdConfiguration = pmdConfiguration;
        this.javaResourceLocator = javaResourceLocator;
        this.settings = settings;
    }

    public Report execute() {
        final Profiler profiler = Profiler.create(LOGGER).startInfo("Execute PMD " + PMDVersion.VERSION);
        final ClassLoader initialClassLoader = Thread.currentThread().getContextClassLoader();

        try (URLClassLoader classLoader = createClassloader()) {
            Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

            return executePmd(classLoader);
        } catch (IOException e) {
            LOGGER.error("Failed to close URLClassLoader.", e);
        } finally {
            Thread.currentThread().setContextClassLoader(initialClassLoader);
            profiler.stopInfo();
        }

        return null;
    }
    
    private File findParentProperty(File file){
  	  boolean pomFind = false;
  	  for(File child : file.listFiles()){
  		  if(child.isFile() && child.getName().equalsIgnoreCase("hepRule.cfg")){
  			  return child;
  		  }else if(child.isFile() && child.getName().equalsIgnoreCase("pom.xml")){
  			  pomFind = true;
  		  }
  	  }
  	  if (pomFind){
  		  return findParentProperty(file.getParentFile());
  	  }
  	  return null;
    }
    
    private File getPropertyFile(File dir, boolean pomFind){
  	  if(dir == null || pomFind){
  		  return null;
  	  }
  	  List<File> folders = new ArrayList<File>();
  	  for(File child : dir.listFiles()){
  		  if(child.isFile() && child.getName().equalsIgnoreCase("hepRule.cfg")){
  			  return child;
  		  }else if(child.isFile() && child.getName().equalsIgnoreCase("pom.xml")){
  			  pomFind = true;
  		  }else if(child.isDirectory()){
  			  folders.add(child);
  		  }
  	  }
  	  if(!pomFind){
  		  for(File child : folders){
  			  File file = getPropertyFile(child, pomFind);
  			  if(file != null){
  				  return file;
  			  }
  		  }
  	  }
  	  return null;
    }


    private Report executePmd(URLClassLoader classLoader) {
        Report report = new Report();

        RuleContext context = new RuleContext();
        File prop = getPropertyFile(fs.baseDir(), false);
        if (prop == null){
        	File parent = fs.baseDir().getParentFile();
        	prop = findParentProperty(parent);
        }
        if(prop != null){
        	System.out.println("配置文件：" + prop.getAbsolutePath());
        	Properties pp = new Properties();
        	FileInputStream fis = null;
        	try {
    			fis = new FileInputStream(prop);
    			pp.load(fis);
    			for(Entry<Object, Object> entry : pp.entrySet()){
    				context.setAttribute(entry.getKey().toString(), entry.getValue());
    			}
    		} catch (IOException e) {
    		} finally {
    			try {
    				fis.close();
    			} catch (IOException e) {
    			}
    		}
        }
        context.setReport(report);

        PmdTemplate pmdFactory = createPmdTemplate(classLoader);
        executeRules(pmdFactory, context, javaFiles(Type.MAIN), PmdConstants.REPOSITORY_KEY);
        executeRules(pmdFactory, context, javaFiles(Type.TEST), PmdConstants.TEST_REPOSITORY_KEY);

        pmdConfiguration.dumpXmlReport(report);

        return report;
    }

    private Iterable<InputFile> javaFiles(Type fileType) {
        final FilePredicates predicates = fs.predicates();
        return fs.inputFiles(
                predicates.and(
                        predicates.hasLanguage(PmdConstants.LANGUAGE_KEY),
                        predicates.hasType(fileType)
                )
        );
    }

    private void executeRules(PmdTemplate pmdFactory, RuleContext ruleContext, Iterable<InputFile> files, String repositoryKey) {
        if (!files.iterator().hasNext()) {
            // Nothing to analyze
            return;
        }

        RuleSets rulesets = createRuleSets(repositoryKey);
        if (rulesets.getAllRules().isEmpty()) {
            // No rule
            return;
        }

        rulesets.start(ruleContext);

        for (InputFile file : files) {
            pmdFactory.process(file, rulesets, ruleContext);
        }

        rulesets.end(ruleContext);
    }

    private RuleSets createRuleSets(String repositoryKey) {
        String rulesXml = dumpXml(rulesProfile, repositoryKey);
        File ruleSetFile = pmdConfiguration.dumpXmlRuleSet(repositoryKey, rulesXml);
        String ruleSetFilePath = ruleSetFile.getAbsolutePath();
        RuleSetFactory ruleSetFactory = new RuleSetFactory();
        try {
            RuleSet ruleSet = ruleSetFactory.createRuleSet(ruleSetFilePath);
            return new RuleSets(ruleSet);
        } catch (RuleSetNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private String dumpXml(ActiveRules rulesProfile, String repositoryKey) {
        final StringWriter writer = new StringWriter();
        final PmdRuleSet ruleSet = PmdRuleSets.from(rulesProfile, repositoryKey);
        ruleSet.writeTo(writer);

        return writer.toString();
    }

    PmdTemplate createPmdTemplate(URLClassLoader classLoader) {
        return PmdTemplate.create(getSourceVersion(), classLoader, fs.encoding());
    }

    /**
     * @return A classloader for PMD that contains all dependencies of the project that shall be analyzed.
     */
    private URLClassLoader createClassloader() {
        Collection<File> classpathElements = javaResourceLocator.classpath();
        List<URL> urls = new ArrayList<>();
        for (File file : classpathElements) {
            try {
                urls.add(file.toURI().toURL());
            } catch (MalformedURLException e) {
                throw new IllegalStateException("Failed to create the project classloader. Classpath element is invalid: " + file, e);
            }
        }
        return new URLClassLoader(urls.toArray(new URL[0]), null);
    }

    private String getSourceVersion() {
        return settings.get(PmdConstants.JAVA_SOURCE_VERSION)
                .orElse(PmdConstants.JAVA_SOURCE_VERSION_DEFAULT_VALUE);
    }

}
