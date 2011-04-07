/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * Copyright (c) 2011, Red Hat Inc. or third-party contributors as
 * indicated by the @author tags or express copyright attribution
 * statements applied by the authors.  All third-party contributions are
 * distributed under license by Red Hat Inc.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, write to:
 * Free Software Foundation, Inc.
 * 51 Franklin Street, Fifth Floor
 * Boston, MA  02110-1301  USA
 */
package org.hibernate.testing.junit4;

import static org.hibernate.testing.TestLogger.LOG;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.dialect.Dialect;
import org.hibernate.internal.util.StringHelper;
import org.hibernate.testing.DialectCheck;
import org.hibernate.testing.FailureExpected;
import org.hibernate.testing.RequiresDialect;
import org.hibernate.testing.RequiresDialectFeature;
import org.hibernate.testing.Skip;
import org.hibernate.testing.SkipForDialect;
import org.junit.Ignore;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;

/**
 * The Hibernate-specific {@link org.junit.runner.Runner} implementation which layers {@link ExtendedFrameworkMethod}
 * support on top of the standard JUnit {@link FrameworkMethod} for extra information after checking to make sure the
 * test should be run.
 *
 * @author Steve Ebersole
 */
public class CustomRunner extends BlockJUnit4ClassRunner {

	private TestClassMetadata testClassMetadata;

	public CustomRunner(Class<?> clazz) throws InitializationError, NoTestsRemainException {
		super( clazz );
	}

	@Override
	protected void collectInitializationErrors(List<Throwable> errors) {
		super.collectInitializationErrors( errors );
		this.testClassMetadata = new TestClassMetadata( getTestClass().getJavaClass() );
		testClassMetadata.validate( errors );
	}

	public TestClassMetadata getTestClassMetadata() {
		return testClassMetadata;
	}

	@Override
	protected Statement withBeforeClasses(Statement statement) {
		return new BeforeClassCallbackHandler(
				this,
				super.withBeforeClasses( statement )
		);
	}

	@Override
	protected Statement withAfterClasses(Statement statement) {
		return new AfterClassCallbackHandler(
				this,
				super.withAfterClasses( statement )
		);
	}


	@Override
	protected Statement methodBlock(FrameworkMethod method) {
		final Statement originalMethodBlock = super.methodBlock( method );
		final ExtendedFrameworkMethod extendedFrameworkMethod = (ExtendedFrameworkMethod) method;
		return new FailureExpectedHandler( originalMethodBlock, testClassMetadata, extendedFrameworkMethod, testInstance );
	}

	private Object testInstance;

	protected Object getTestInstance() throws Exception {
		if ( testInstance == null ) {
			testInstance = super.createTest();
		}
		return testInstance;
	}

	@Override
	protected Object createTest() throws Exception {
		return getTestInstance();
	}

	private List<FrameworkMethod> computedTestMethods;

	@Override
	protected List<FrameworkMethod> computeTestMethods() {
		if ( computedTestMethods == null ) {
			computedTestMethods = doComputation();
		}
		return computedTestMethods;
	}

	protected List<FrameworkMethod> doComputation() {
        // Next, get all the test methods as understood by JUnit
        final List<FrameworkMethod> methods = super.computeTestMethods();

        // Now process that full list of test methods and build our custom result
        final List<FrameworkMethod> result = new ArrayList<FrameworkMethod>();
		final boolean doValidation = Boolean.getBoolean( Helper.VALIDATE_FAILURE_EXPECTED );
		int testCount = 0;

		Ignore virtualIgnore;

		for ( FrameworkMethod frameworkMethod : methods ) {
			// potentially ignore based on expected failure
            final FailureExpected failureExpected = Helper.locateAnnotation( FailureExpected.class, frameworkMethod, getTestClass() );
			if ( failureExpected != null && !doValidation ) {
				virtualIgnore = new IgnoreImpl( Helper.extractIgnoreMessage( failureExpected, frameworkMethod ) );
			}
			else {
				virtualIgnore = convertSkipToIgnore( frameworkMethod );
			}

			testCount++;
			LOG.trace( "adding test " + Helper.extractTestName( frameworkMethod ) + " [#" + testCount + "]" );
			result.add( new ExtendedFrameworkMethod( frameworkMethod, virtualIgnore, failureExpected ) );
		}
		return result;
	}

	@SuppressWarnings( {"ClassExplicitlyAnnotation"})
	public static class IgnoreImpl implements Ignore {
		private final String value;

		public IgnoreImpl(String value) {
			this.value = value;
		}

		@Override
		public String value() {
			return value;
		}

		@Override
		public Class<? extends Annotation> annotationType() {
			return Ignore.class;
		}
	}

	private static Dialect dialect = determineDialect();

	private static Dialect determineDialect() {
		try {
			return Dialect.getDialect();
		}
		catch( Exception e ) {
			return new Dialect() {
			};
		}
	}

	protected Ignore convertSkipToIgnore(FrameworkMethod frameworkMethod) {
		// @Skip
		Skip skip = Helper.locateAnnotation( Skip.class, frameworkMethod, getTestClass() );
		if ( skip != null ) {
			if ( isMatch( skip.condition() ) ) {
				return buildIgnore( skip );
			}
		}

		// @SkipForDialect
		SkipForDialect skipForDialectAnn = Helper.locateAnnotation( SkipForDialect.class, frameworkMethod, getTestClass() );
		if ( skipForDialectAnn != null ) {
			for ( Class<? extends Dialect> dialectClass : skipForDialectAnn.value() ) {
				if ( skipForDialectAnn.strictMatching() ) {
					if ( dialectClass.equals( dialect.getClass() ) ) {
						return buildIgnore( skipForDialectAnn );
					}
				}
				else {
					if ( dialectClass.isInstance( dialect ) ) {
						return buildIgnore( skipForDialectAnn );
					}
				}
			}
		}

		// @RequiresDialect
		RequiresDialect requiresDialectAnn = Helper.locateAnnotation( RequiresDialect.class, frameworkMethod, getTestClass() );
		if ( requiresDialectAnn != null ) {
			boolean foundMatch = false;
			for ( Class<? extends Dialect> dialectClass : requiresDialectAnn.value() ) {
				foundMatch = requiresDialectAnn.strictMatching()
						? dialectClass.equals( dialect.getClass() )
						: dialectClass.isInstance( dialect );
				if ( foundMatch ) {
					break;
				}
			}
			if ( !foundMatch ) {
				return buildIgnore( requiresDialectAnn );
			}
		}

		// @RequiresDialectFeature
		RequiresDialectFeature requiresDialectFeatureAnn = Helper.locateAnnotation( RequiresDialectFeature.class, frameworkMethod, getTestClass() );
		if ( requiresDialectFeatureAnn != null ) {
			Class<? extends DialectCheck> checkClass = requiresDialectFeatureAnn.value();
			try {
				DialectCheck check = checkClass.newInstance();
				if ( !check.isMatch( dialect ) ) {
					return buildIgnore( requiresDialectFeatureAnn );
				}
			}
			catch (RuntimeException e) {
				throw e;
			}
			catch (Exception e) {
				throw new RuntimeException( "Unable to instantiate DialectCheck", e );
			}
		}

		return null;
	}

	private Ignore buildIgnore(Skip skip) {
		return new IgnoreImpl( "@Skip : " + skip.message() );
	}

	private Ignore buildIgnore(SkipForDialect skip) {
		return buildIgnore( "@SkipForDialect match", skip.comment(), skip.jiraKey() );
	}

	private Ignore buildIgnore(String reason, String comment, String jiraKey) {
		StringBuilder buffer = new StringBuilder( reason );
		if ( StringHelper.isNotEmpty( comment ) ) {
			buffer.append( "; " ).append( comment );
		}

		if ( StringHelper.isNotEmpty( jiraKey ) ) {
			buffer.append( " (" ).append( jiraKey ).append( ')' );
		}

		return new IgnoreImpl( buffer.toString() );
	}

	private Ignore buildIgnore(RequiresDialect requiresDialect) {
		return buildIgnore( "@RequiresDialect non-match", requiresDialect.comment(), requiresDialect.jiraKey() );
	}

	private Ignore buildIgnore(RequiresDialectFeature requiresDialectFeature) {
		return buildIgnore( "@RequiresDialectFeature non-match", requiresDialectFeature.comment(), requiresDialectFeature.jiraKey() );
	}

	private boolean isMatch(Class<? extends Skip.Matcher> condition) {
		try {
			Skip.Matcher matcher = condition.newInstance();
			return matcher.isMatch();
		}
		catch (Exception e) {
			throw new MatcherInstantiationException( condition, e );
		}
	}

	private static class MatcherInstantiationException extends RuntimeException {
		private MatcherInstantiationException(Class<? extends Skip.Matcher> matcherClass, Throwable cause) {
			super( "Unable to instantiate specified Matcher [" + matcherClass.getName(), cause );
		}
	}

}
