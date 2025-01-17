/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gbif.nameparser;

import org.gbif.nameparser.api.*;
import org.gbif.nameparser.utils.CallerBlocksPolicy;
import org.gbif.nameparser.utils.NamedThreadFactory;

import java.util.concurrent.*;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;

/**
 * The default GBIF name parser build on regular expressions.
 * In order to avoid long running regex matches it runs the core parsing in a background threadpool
 * which is shared across all instances of the parser.
 *
 * Make sure to reuse the instance as much as possible and don't forget to close it for the threads to shutdown properly.
 */
public class NameParserGBIF implements NameParser {
  public static final String THREAD_NAME = "NameParser-worker";
  private static final Logger LOG = LoggerFactory.getLogger(NameParserGBIF.class);

  /**
   * We use a cached daemon threadpool to run the parsing in the background so we can control
   * timeouts. If idle the pool shrinks to no threads after 1 seconds plus configured timeout.
   */
  private final ExecutorService exec;
  private final long timeout;  // max parsing time in milliseconds
  private final ParserConfigs configs = new ParserConfigs();

  /**
   * The default name parser without an explicit monomials list using the default timeout of 1s for parsing.
   */
  public NameParserGBIF() {
    this(1000);
  }

  /**
   * @param timeout max parsing time in milliseconds
   */
  public NameParserGBIF(long timeout) {
    this(timeout, 0, 100);
  }

  /**
   * @param timeout max parsing time in milliseconds
   */
  public NameParserGBIF(long timeout, int corePoolSize, int maxPoolSize) {
    this(timeout, new ThreadPoolExecutor(corePoolSize, maxPoolSize,
        2*timeout, TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(),
        new NamedThreadFactory(THREAD_NAME, Thread.NORM_PRIORITY, true),
        new CallerBlocksPolicy(timeout)));
  }

  /**
   * The default name parser without an explicit monomials list using the given timeout in milliseconds for parsing.
   */
  public NameParserGBIF(long timeout, ExecutorService executorService) {
    Preconditions.checkArgument(timeout > 0, "Timeout needs to be at least 1ms");
    LOG.debug("Create new name parser with timeout={}", timeout);
    this.timeout = timeout;
    this.exec = executorService;
  }

  /**
   * @deprecated provide rank and code parameters
   */
  @Deprecated
  public ParsedName parse(String scientificName) throws UnparsableNameException {
    return parse(scientificName, Rank.UNRANKED);
  }

  @Override
  public ParsedAuthorship parseAuthorship(String authorship) throws UnparsableNameException {
    if (Strings.isNullOrEmpty(authorship)) {
      throw new UnparsableNameException.UnparsableAuthorshipException(authorship);
    }

    // override exists?
    ParsedAuthorship over = configs.forAuthorship(authorship);
    if (over != null) {
      LOG.debug("Manual override found for authorship: {}", authorship);
      return over;
    }

    AuthorshipParsingJob job = new AuthorshipParsingJob(authorship, configs);
    FutureTask<ParsedName> task = new FutureTask<>(job);
    exec.execute(task);

    try {
      return task.get(timeout, TimeUnit.MILLISECONDS);

    } catch (InterruptedException e) {
      LOG.warn("Thread got interrupted. Stop authorship parse job {}", authorship, e);

    } catch (ExecutionException e) {
      // unwrap UnparsableNameException
      if (e.getCause() instanceof UnparsableNameException) {
        throw (UnparsableNameException) e.getCause();

      } else {
        LOG.warn("ExecutionException when parsing authorship: {}", authorship, e);
      }

    } catch (TimeoutException e) {
      // parsing timeout
      LOG.warn("Parsing timeout for authorship: {}", authorship);
      task.cancel(true);
    }

    throw new UnparsableNameException.UnparsableAuthorshipException(authorship);
  }

  /**
   * @deprecated provide rank and code parameters
   */
  @Deprecated
  public ParsedName parse(final String scientificName, Rank rank) throws UnparsableNameException {
    return parse(scientificName, rank, null);
  }
  
  /**
   * Fully parse the supplied name also trying to extract authorships, a conceptual sec reference, remarks or notes
   * on the nomenclatural status. In some cases the authorship parsing proves impossible and this nameparser will
   * return null.
   *
   * For strings which are null, empty, no scientific names and scientific names that cannot be expressed by the ParsedName class
   * the parser will throw an UnparsableException with a given NameType and the original, unparsed name. This is the
   * case for all virus names and proper hybrid formulas, so make sure you catch and process this exception.
   *
   * @param scientificName the full scientific name to parse
   * @param rank the rank of the name if it is known externally. Helps identifying infrageneric names vs bracket authors
   * @param code the nomenclatural code the name falls into. Null if unknown
   *
   * @throws UnparsableNameException
   */
  @Override
  public ParsedName parse(final String scientificName, Rank rank, @Nullable NomCode code) throws UnparsableNameException {
    if (Strings.isNullOrEmpty(scientificName)) {
      throw new UnparsableNameException(NameType.NO_NAME, scientificName);
    }
    
    FutureTask<ParsedName> task = new FutureTask<>(new ParsingJob(scientificName, rank == null ? Rank.UNRANKED : rank, code, configs));
    exec.execute(task);

    try {
      return task.get(timeout, TimeUnit.MILLISECONDS);
      
    } catch (InterruptedException e) {
      LOG.warn("Thread got interrupted. Stop parse job {} {}", rank, scientificName, e);
      
    } catch (ExecutionException e) {
      // unwrap UnparsableNameException
      if (e.getCause() instanceof UnparsableNameException) {
        throw (UnparsableNameException) e.getCause();
        
      } else {
        LOG.warn("ExecutionException when parsing name: {}", scientificName, e);
      }
      
    } catch (TimeoutException e) {
      // parsing timeout
      LOG.warn("Parsing timeout for name: {}", scientificName);
      task.cancel(true);
    }
    
    throw new UnparsableNameException(NameType.SCIENTIFIC, scientificName);
  }

  public ParserConfigs getConfigs() {
    return configs;
  }

  @Override
  public void close() throws Exception {
    LOG.info("Shutting down name parser worker threads");
    exec.shutdown();
    if (exec.awaitTermination(1, TimeUnit.SECONDS)) {
      LOG.info("shutdown succeeded orderly");
    } else {
      int count = exec.shutdownNow().size();
      LOG.warn("forced shutdown of name parser workers, discarding {} queued parsing tasks", count);
    }
  }
}
