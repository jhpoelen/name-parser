package org.gbif.nameparser.api;

/**
 * Standard warning strings as used by the parser
 */
public class Warnings {
  public static final String NULL_EPITHET = "epithet with literal value null";
  public static final String UNUSUAL_CHARACTERS = "unusual characters";
  public static final String SUBSPECIES_ASSIGNED = "Name was considered species but contains infraspecific epithet";
  public static final String LC_MONOMIAL = "lower case monomial match";
  public static final String INDETERMINED = "indetermined name missing its terminal epithet";
  public static final String HIGHER_RANK_BINOMIAL = "binomial with rank higher than species aggregate";
  public static final String QUESTION_MARKS_REMOVED = "question marks removed";
  public static final String REPL_ENCLOSING_QUOTE = "removed enclosing quotes";
  public static final String MISSING_GENUS = "epithet without genus";
  public static final String RANK_MISMATCH = "rank does not fit the parsed name";
  public static final String HTML_ENTITIES = "html entities unescaped";
  public static final String XML_TAGS = "xml tags removed";
  public static final String BLACKLISTED_EPITHET = "blacklisted epithet used";
  public static final String NOMENCLATURAL_REFERENCE = "nomenclatural reference removed";

  private Warnings() {
  }
}
