package org.richfaces.integration;

import java.io.File;

import org.jboss.shrinkwrap.api.Filters;
import org.jboss.shrinkwrap.api.GenericArchive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.importer.ExplodedImporter;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.richfaces.arquillian.configuration.FundamentalTestConfiguration;
import org.richfaces.arquillian.configuration.FundamentalTestConfigurationContext;
import org.richfaces.deployment.Deployment;

public class OutputDeployment extends Deployment {

    private final FundamentalTestConfiguration configuration = FundamentalTestConfigurationContext.getProxy();

    public OutputDeployment(Class<?> testClass) {
        super(testClass);

        if (configuration.isCurrentRichFacesVersion()) {

            addCurrentProjectClasses();

            this.addMavenDependency(
                    "org.richfaces:richfaces-core",
                    "org.richfaces:richfaces-a4j");

        } else {
            String version = configuration.getRichFacesVersion();
            this.addMavenDependency(
                "org.richfaces:richfaces-core:" + version,
                "org.richfaces:richfaces-a4j:" + version);
        }

        archive().addAsWebInfResource(new File("src/test/resources/beans.xml"));

    }

    private void addCurrentProjectClasses() {
        JavaArchive jar = ShrinkWrap.create(JavaArchive.class, "richfaces.jar");
        jar.merge(
                ShrinkWrap.create(GenericArchive.class).as(ExplodedImporter.class).importDirectory("target/classes/")
                        .as(GenericArchive.class), "/", Filters.includeAll());
        archive().addAsLibrary(jar);
    }
}
