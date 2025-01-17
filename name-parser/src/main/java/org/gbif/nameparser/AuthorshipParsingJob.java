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

import org.gbif.nameparser.api.NameType;
import org.gbif.nameparser.api.ParsedName;
import org.gbif.nameparser.api.Rank;
import org.gbif.nameparser.api.UnparsableNameException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Strings;

public class AuthorshipParsingJob extends ParsingJob {

    /**
     * Pattern that matches an authorship only.
     * This is more reliable than parsing a name with authorship as we know there should be no epithets, just an authorship.
     */
    static final Pattern AUTHORSHIP_PATTERN = Pattern.compile("^" +
            "(?:\\(" +
            // #1/2/3 basionym authorship (ex/auth/sanct)
            "(?:" + AUTHORSHIP + "\\.?)?" +
            // #4 basionym year
            "[, ]?(" + YEAR_LOOSE + ")?" +
            "\\))?" +

            // #5/6/7 authorship (ex/auth/sanct)
            "(?:" + AUTHORSHIP + ")?" +
            // #8 year with or without brackets
            "(?: ?\\(?,?(" + YEAR_LOOSE + ")\\)?)?" +

            // #9 any remainder
            "(\\b.*?)??$");


    /**
     * @param authorship the full authorship incl basionym, ex authors, nomenclatural reference and remarks
     * @param configs
     */
    public AuthorshipParsingJob(String authorship, ParserConfigs configs) {
        super(authorship, Rank.UNRANKED, null, configs);
    }


    /**
     * Fully parse the supplied name also trying to extract authorships, a conceptual sec reference, remarks or notes
     * on the nomenclatural status. In some cases the authorship parsing proves impossible and this nameparser will
     * return null.
     *
     * For strings which are no scientific names and scientific names that cannot be expressed by the ParsedName class
     * the parser will throw an UnparsableException with a given NameType and the original, unparsed name. This is the
     * case for all virus names and proper hybrid formulas, so make sure you catch and process this exception.
     *
     * @throws UnparsableNameException
     */
    @Override
    public ParsedName call() throws UnparsableNameException, InterruptedException {
        long start = 0;
        if (LOG.isDebugEnabled()) {
            start = System.currentTimeMillis();
        }

        // clean name, removing seriously wrong things
        name = preClean(scientificName);

        // preparse nomenclatural references
        preparseNomRef();
        // remove placeholders from infragenerics and authors and set type
        removePlaceholderAuthor();
        // detect further unparsable names
        detectFurtherUnparsableNames();

        // normalise name
        name = normalize(name);
        if (Strings.isNullOrEmpty(name)) {
            unparsable();
        }
        // extract nom.illeg. and other nomen status notes
        extractNomStatus();
        // extract sec reference
        extractSecReference();
        // extract bibliographic in references
        extractPublishedIn();

        // normalize ex hort. (for gardeners, often used as ex names) spelled in lower case
        name = normalizeHort(name);
        name = noQMarks(name);
        // replace different kind of brackets with ()
        name = normBrackets(name);
        name = normWsPunct(name);

        // main authorship parsing
        parseNormalisedAuthorship();

        if (state != null) {
            pn.setState(state);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Parsing time: {} for {}", (System.currentTimeMillis() - start), pn);
        }
        return pn;
    }

    @Override
    void unparsable(NameType type) throws UnparsableNameException {
        throw new UnparsableNameException.UnparsableAuthorshipException(scientificName);
    }
    void unparsable() throws UnparsableNameException {
        unparsable(NameType.NO_NAME);
    }

    private void parseNormalisedAuthorship() throws UnparsableNameException, InterruptedException {
        LOG.debug("Parse normed authorship: {}", name);
        Matcher m = matcherInterruptable(AUTHORSHIP_PATTERN, name);
        if (m.find()) {
            // #9 any remainder
            if (StringUtils.isBlank(m.group(9))) {
                pn.setState(ParsedName.State.COMPLETE);
            } else {
                pn.setState(ParsedName.State.PARTIAL);
                pn.addUnparsed(m.group(9).trim());
                LOG.debug("Partial match with unparsed remains \"{}\" for: {}", pn.getUnparsed(), name);
            }
            if (LOG.isDebugEnabled()) {
                logMatcher(m);
            }

            // #1/2/3/4 basionym authorship (ex/auth/sanct/year)
            pn.setBasionymAuthorship(parseAuthorship(m.group(1), m.group(2), m.group(4)));
            // #5/6/7/8 authorship (ex/auth/sanct/year)
            pn.setCombinationAuthorship(parseAuthorship(m.group(5), m.group(6), m.group(8)));
            // sanctioning author
            if (m.group(7) != null) {
                pn.setSanctioningAuthor(m.group(7));
            }

        } else {
            unparsable();
        }
    }
}
