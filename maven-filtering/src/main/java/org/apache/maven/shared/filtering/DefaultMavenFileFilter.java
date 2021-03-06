package org.apache.maven.shared.filtering;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;
import org.apache.maven.shared.utils.StringUtils;
import org.apache.maven.shared.utils.io.FileUtils;
import org.apache.maven.shared.utils.io.FileUtils.FilterWrapper;
import org.codehaus.plexus.interpolation.InterpolationPostProcessor;
import org.codehaus.plexus.interpolation.PrefixAwareRecursionInterceptor;
import org.codehaus.plexus.interpolation.PrefixedObjectValueSource;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.RecursionInterceptor;
import org.codehaus.plexus.interpolation.SimpleRecursionInterceptor;
import org.codehaus.plexus.interpolation.SingleResponseValueSource;
import org.codehaus.plexus.interpolation.ValueSource;
import org.codehaus.plexus.interpolation.multi.MultiDelimiterStringSearchInterpolator;
import org.codehaus.plexus.logging.AbstractLogEnabled;
import org.sonatype.plexus.build.incremental.BuildContext;

/**
 * @author Olivier Lamy
 *
 * @plexus.component role="org.apache.maven.shared.filtering.MavenFileFilter"
 * role-hint="default"
 */
public class DefaultMavenFileFilter
    extends AbstractLogEnabled
    implements MavenFileFilter
{

    /**
     * @plexus.requirement
     */
    private BuildContext buildContext;

    public void copyFile( File from, File to, boolean filtering, MavenProject mavenProject, List<String> filters,
                          boolean escapedBackslashesInFilePath, String encoding, MavenSession mavenSession )
        throws MavenFilteringException
    {
        MavenResourcesExecution mre = new MavenResourcesExecution();
        mre.setMavenProject( mavenProject );
        mre.setFileFilters( filters );
        mre.setEscapeWindowsPaths( escapedBackslashesInFilePath );
        mre.setMavenSession( mavenSession );
        mre.setInjectProjectBuildFilters( true );

        List<FileUtils.FilterWrapper> filterWrappers = getDefaultFilterWrappers( mre );
        copyFile( from, to, filtering, filterWrappers, encoding );
    }


    public void copyFile( MavenFileFilterRequest mavenFileFilterRequest )
        throws MavenFilteringException
    {
        List<FilterWrapper> filterWrappers = getDefaultFilterWrappers( mavenFileFilterRequest );

        copyFile( mavenFileFilterRequest.getFrom(), mavenFileFilterRequest.getTo(),
                  mavenFileFilterRequest.isFiltering(), filterWrappers, mavenFileFilterRequest.getEncoding() );
    }


    public void copyFile( File from, File to, boolean filtering, List<FileUtils.FilterWrapper> filterWrappers,
                          String encoding )
        throws MavenFilteringException
    {
        // overwrite forced to false to preserve backward comp
        copyFile( from, to, filtering, filterWrappers, encoding, false );
    }


    public void copyFile( File from, File to, boolean filtering, List<FileUtils.FilterWrapper> filterWrappers,
                          String encoding, boolean overwrite )
        throws MavenFilteringException
    {
        try
        {
            if ( filtering )
            {
                if ( getLogger().isDebugEnabled() )
                {
                    getLogger().debug( "filtering " + from.getPath() + " to " + to.getPath() );
                }
                FileUtils.FilterWrapper[] wrappers = filterWrappers.toArray(
                    new FileUtils.FilterWrapper[filterWrappers.size()] );
                FileUtils.copyFile( from, to, encoding, wrappers );
            }
            else
            {
                if ( getLogger().isDebugEnabled() )
                {
                    getLogger().debug( "copy " + from.getPath() + " to " + to.getPath() );
                }
                FileUtils.copyFile( from, to, encoding, new FileUtils.FilterWrapper[0], overwrite );
            }

            buildContext.refresh( to );
        }
        catch ( IOException e )
        {
            throw new MavenFilteringException( e.getMessage(), e );
        }

    }

    /**
     * @see org.apache.maven.shared.filtering.MavenFileFilter#getDefaultFilterWrappers(org.apache.maven.project.MavenProject, java.util.List, boolean, org.apache.maven.execution.MavenSession)
     * @deprecated
     */
    public List<FileUtils.FilterWrapper> getDefaultFilterWrappers( final MavenProject mavenProject,
                                                                   List<String> filters,
                                                                   final boolean escapedBackslashesInFilePath,
                                                                   MavenSession mavenSession )
        throws MavenFilteringException
    {
        return getDefaultFilterWrappers( mavenProject, filters, escapedBackslashesInFilePath, mavenSession, null );
    }


    public List<FileUtils.FilterWrapper> getDefaultFilterWrappers( final MavenProject mavenProject, List<String> filters,
                                                                   final boolean escapedBackslashesInFilePath,
                                                                   MavenSession mavenSession,
                                                                   MavenResourcesExecution mavenResourcesExecution )
        throws MavenFilteringException
    {

        MavenResourcesExecution mre =
            mavenResourcesExecution == null ? new MavenResourcesExecution() : mavenResourcesExecution.copyOf();

        mre.setMavenProject( mavenProject );
        mre.setMavenSession( mavenSession );
        mre.setFilters( filters );
        mre.setEscapedBackslashesInFilePath( escapedBackslashesInFilePath );

        return getDefaultFilterWrappers( mre );

    }

    public List<FileUtils.FilterWrapper> getDefaultFilterWrappers( final AbstractMavenFilteringRequest req )
        throws MavenFilteringException
    {
        // backup values
        boolean supportMultiLineFiltering = req.isSupportMultiLineFiltering();

        // compensate for null parameter value.
        final AbstractMavenFilteringRequest request = req == null ? new MavenFileFilterRequest() : req;

        request.setSupportMultiLineFiltering( supportMultiLineFiltering );

        // Here we build some properties which will be used to read some properties files
        // to interpolate the expression ${ } in this properties file

        // Take a copy of filterProperties to ensure that evaluated filterTokens are not propagated
        // to subsequent filter files. Note: this replicates current behaviour and seems to make sense.

        final Properties baseProps = new Properties();

        // Project properties
        if ( request.getMavenProject() != null )
        {
            baseProps.putAll( request.getMavenProject().getProperties() == null
                                  ? Collections.emptyMap()
                                  : request.getMavenProject().getProperties() );
        }
        // TODO this is NPE free but do we consider this as normal
        // or do we have to throw an MavenFilteringException with mavenSession cannot be null
        if ( request.getMavenSession() != null )
        {
            // execution properties wins
            baseProps.putAll( request.getMavenSession().getExecutionProperties() );
        }

        // now we build properties to use for resources interpolation

        final Properties filterProperties = new Properties();

        File basedir = request.getMavenProject() != null ? request.getMavenProject().getBasedir() : new File( "." ); 

        loadProperties( filterProperties, basedir, request.getFileFilters(), baseProps );
        if ( filterProperties.size() < 1 )
        {
            filterProperties.putAll( baseProps );
        }

        if ( request.getMavenProject() != null )
        {
            if ( request.isInjectProjectBuildFilters() )
            {
                @SuppressWarnings( "unchecked" )
                List<String> buildFilters = new ArrayList<String>( request.getMavenProject().getBuild().getFilters() );

                // JDK-8015656: (coll) unexpected NPE from removeAll 
                if ( request.getFileFilters() != null )
                {
                    buildFilters.removeAll( request.getFileFilters() );
                }

                loadProperties( filterProperties, basedir, buildFilters, baseProps );
            }

            // Project properties
            filterProperties.putAll( request.getMavenProject().getProperties() == null
                                         ? Collections.emptyMap()
                                         : request.getMavenProject().getProperties() );
        }
        if ( request.getMavenSession() != null )
        {
            // execution properties wins
            filterProperties.putAll( request.getMavenSession().getExecutionProperties() );
        }

        if ( request.getAdditionalProperties() != null )
        {
            // additional properties wins
            filterProperties.putAll( request.getAdditionalProperties() );
        }

        List<FileUtils.FilterWrapper> defaultFilterWrappers = request == null
            ? new ArrayList<FileUtils.FilterWrapper>( 1 )
            : new ArrayList<FileUtils.FilterWrapper>( request.getDelimiters().size() + 1 );

        if ( getLogger().isDebugEnabled() )
        {
            getLogger().debug( "properties used " + filterProperties );
        }

        final ValueSource propertiesValueSource = new PropertiesBasedValueSource( filterProperties );

        if ( request != null )
        {
            FileUtils.FilterWrapper wrapper =
                new Wrapper( request.getDelimiters(), request.getMavenProject(), request.getMavenSession(),
                             propertiesValueSource, request.getProjectStartExpressions(), request.getEscapeString(),
                             request.isEscapeWindowsPaths(), request.isSupportMultiLineFiltering() );

            defaultFilterWrappers.add( wrapper );
        }

        return defaultFilterWrappers;
    }

    /**
     * default visibility only for testing reason !
     */
    void loadProperties( Properties filterProperties, File basedir, List<String> propertiesFilePaths, Properties baseProps )
        throws MavenFilteringException
    {
        if ( propertiesFilePaths != null )
        {
            Properties workProperties = new Properties();
            workProperties.putAll( baseProps );

            for ( String filterFile : propertiesFilePaths )
            {
                if ( StringUtils.isEmpty( filterFile ) )
                {
                    // skip empty file name
                    continue;
                }
                try
                {
                    File propFile = FileUtils.resolveFile( basedir, filterFile );
                    Properties properties = PropertyUtils.loadPropertyFile( propFile, workProperties );
                    filterProperties.putAll( properties );
                    workProperties.putAll( properties );
                }
                catch ( IOException e )
                {
                    throw new MavenFilteringException( "Error loading property file '" + filterFile + "'", e );
                }
            }
        }
    }

    private static final class Wrapper
        extends FileUtils.FilterWrapper
    {

        private LinkedHashSet<String> delimiters;

        private MavenProject project;

        private ValueSource propertiesValueSource;

        private List<String> projectStartExpressions;

        private String escapeString;

        private boolean escapeWindowsPaths;

        private final MavenSession mavenSession;

        private boolean supportMultiLineFiltering;

        Wrapper( LinkedHashSet<String> delimiters, MavenProject project, MavenSession mavenSession,
                 ValueSource propertiesValueSource, List<String> projectStartExpressions, String escapeString,
                 boolean escapeWindowsPaths, boolean supportMultiLineFiltering )
        {
            super();
            this.delimiters = delimiters;
            this.project = project;
            this.mavenSession = mavenSession;
            this.propertiesValueSource = propertiesValueSource;
            this.projectStartExpressions = projectStartExpressions;
            this.escapeString = escapeString;
            this.escapeWindowsPaths = escapeWindowsPaths;
            this.supportMultiLineFiltering = supportMultiLineFiltering;
        }

        public Reader getReader( Reader reader )
        {
            MultiDelimiterStringSearchInterpolator interpolator = new MultiDelimiterStringSearchInterpolator();
            interpolator.setDelimiterSpecs( delimiters );

            RecursionInterceptor ri = null;
            if ( projectStartExpressions != null && !projectStartExpressions.isEmpty() )
            {
                ri = new PrefixAwareRecursionInterceptor( projectStartExpressions, true );
            }
            else
            {
                ri = new SimpleRecursionInterceptor();
            }

            interpolator.addValueSource( propertiesValueSource );

            if ( project != null )
            {
                interpolator.addValueSource( new PrefixedObjectValueSource( projectStartExpressions, project, true ) );
            }

            if ( mavenSession != null )
            {
                interpolator.addValueSource( new PrefixedObjectValueSource( "session", mavenSession ) );

                final Settings settings = mavenSession.getSettings();
                if ( settings != null )
                {
                    interpolator.addValueSource( new PrefixedObjectValueSource( "settings", settings ) );
                    interpolator.addValueSource(
                        new SingleResponseValueSource( "localRepository", settings.getLocalRepository() ) );
                }
            }

            interpolator.setEscapeString( escapeString );

            if ( escapeWindowsPaths )
            {
                interpolator.addPostProcessor( new InterpolationPostProcessor()
                {
                    public Object execute( String expression, Object value )
                    {
                        if ( value instanceof String )
                        {
                            return FilteringUtils.escapeWindowsPath( (String) value );
                        }

                        return value;
                    }
                } );
            }

            MultiDelimiterInterpolatorFilterReaderLineEnding filterReader =
                new MultiDelimiterInterpolatorFilterReaderLineEnding( reader, interpolator, supportMultiLineFiltering );
            filterReader.setRecursionInterceptor( ri );
            filterReader.setDelimiterSpecs( delimiters );

            filterReader.setInterpolateWithPrefixPattern( false );
            filterReader.setEscapeString( escapeString );

            return filterReader;
        }

    }

}
