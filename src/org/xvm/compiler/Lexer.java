package org.xvm.compiler;


import java.math.BigDecimal;
import java.math.BigInteger;

import java.net.IDN;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.NoSuchElementException;

import java.util.function.Consumer;

import org.xvm.asm.ErrorListener;

import org.xvm.asm.Version;
import org.xvm.compiler.Token.Id;

import org.xvm.util.PackedInteger;
import org.xvm.util.Severity;

import static org.xvm.util.Handy.hexitValue;
import static org.xvm.util.Handy.isAsciiLetter;
import static org.xvm.util.Handy.isDigit;
import static org.xvm.util.Handy.isHexit;
import static org.xvm.util.Handy.parseDelimitedString;
import static org.xvm.util.Handy.quotedChar;


/**
 * An XTC source code parser supporting both demand-based and stream-based
 * parsing.
 */
public class Lexer
        implements Iterator<Token>
    {
    // ----- constructors ------------------------------------------------------

    /**
     * Construct an XTC lexical analyzer.
     *
     * @param source  the source to parse
     */
    public Lexer(Source source, ErrorListener errorListener)
        {
        if (source == null)
            {
            throw new IllegalArgumentException("Source required");
            }
        if (errorListener == null)
            {
            throw new IllegalArgumentException("ErrorListener required");
            }

        m_source        = source;
        m_errorListener = errorListener;

        eatWhitespace();
        }

    /**
     * @param parent a Lexer that this Lexer can delegate to
     */
    protected Lexer(Lexer parent)
        {
        m_source        = parent.m_source;
        m_errorListener = parent.m_errorListener;
        }


    // ----- Iterator methods --------------------------------------------------

    @Override
    public boolean hasNext()
        {
        return m_source.hasNext();
        }

    @Override
    public Token next()
        {
        boolean fWhitespaceBefore = m_fWhitespace;
        final Token token = eatToken();
        boolean fWhitespaceAfter = eatWhitespace();
        token.noteWhitespace(fWhitespaceBefore, fWhitespaceAfter);
        return token;
        }

    // ----- public API --------------------------------------------------------

    /**
     * Lexically analyze the source, emitting a stream of tokens to the
     * specified consumer.
     *
     * @param consumer  the Token Consumer
     */
    public void emit(Consumer<Token> consumer)
        {
        final Source source = m_source;
        while (source.hasNext())
            {
            consumer.accept(next());
            }
        }

    /**
     * Create a temporary lexer that provides a stream of tokens as specified.
     *
     * @param atoken  the tokens to stream
     *
     * @return a new Lexer
     */
    public Lexer createLexer(Token[] atoken)
        {
        return new Lexer(this)
            {
            int iNext = 0;

            @Override
            public boolean hasNext()
                {
                return iNext < atoken.length;
                }

            @Override
            public Token next()
                {
                if (hasNext())
                    {
                    return atoken[iNext++];
                    }
                throw new NoSuchElementException();
                }
            };
        }


    // ----- internal ----------------------------------------------------------

    /**
     * Eat the characters defined as whitespace, which include line terminators
     * and the file terminator. Whitespace does not include comments.
     */
    protected boolean eatWhitespace()
        {
        boolean fWhitespace = false;
        final Source source = m_source;
        while (source.hasNext())
            {
            if (isWhitespace(nextChar()))
                {
                fWhitespace = true;
                }
            else
                {
                // put back the non-whitespace character
                source.rewind();
                break;
                }
            }
        m_fWhitespace = fWhitespace;
        return fWhitespace;
        }

    /**
     * Parse a single token.
     *
     * @return the next Token
     */
    protected Token eatToken()
        {
        final Source source   = m_source;
        final long   lInitPos = source.getPosition();
        final char   chInit   = nextChar();

        switch (chInit)
            {
            case '{':
                return new Token(lInitPos, source.getPosition(), Id.L_CURLY);
            case '}':
                return new Token(lInitPos, source.getPosition(), Id.R_CURLY);
            case '(':
                return new Token(lInitPos, source.getPosition(), Id.L_PAREN);
            case ')':
                return new Token(lInitPos, source.getPosition(), Id.R_PAREN);
            case '[':
                return new Token(lInitPos, source.getPosition(), Id.L_SQUARE);
            case ']':
                return new Token(lInitPos, source.getPosition(), Id.R_SQUARE);

            case ';':
                return new Token(lInitPos, source.getPosition(), Id.SEMICOLON);
            case ',':
                return new Token(lInitPos, source.getPosition(), Id.COMMA);

            case '.':
                if (source.hasNext())
                    {
                    switch (nextChar())
                        {
                        case '.':
                            if (source.hasNext())
                                {
                                switch (nextChar())
                                    {
                                    case '.':
                                        return new Token(lInitPos, source.getPosition(), Id.ELLIPSIS);

                                    case '/':
                                        return new Token(lInitPos, source.getPosition(), Id.DIR_PARENT);
                                    }
                                source.rewind();
                                }
                            return new Token(lInitPos, source.getPosition(), Id.DOTDOT);

                        case '/':
                            return new Token(lInitPos, source.getPosition(), Id.DIR_CUR);

                        case '0': case '1': case '2': case '3': case '4':
                        case '5': case '6': case '7': case '8': case '9':
                            source.rewind();
                            source.rewind();
                            return eatNumericLiteral();
                        }
                    source.rewind();
                    }
                return new Token(lInitPos, source.getPosition(), Id.DOT);

            case '$':
                if (source.hasNext())
                    {
                    switch (nextChar())
                        {
                        case '\"':
                            return eatTemplateLiteral(lInitPos);

                        case '|':
                            return eatMultilineTemplateLiteral(lInitPos);

                        case '/':
                            // it is a file name
                            source.rewind();
                            return new Token(lInitPos, source.getPosition(), Id.STR_FILE);

                        case '.':
                            if (source.hasNext())
                                {
                                switch (nextChar())
                                    {
                                    case '.':
                                        if (source.hasNext())
                                            {
                                            if (nextChar() == '/')
                                                {
                                                // it is a file name
                                                source.rewind();
                                                return new Token(lInitPos, source.getPosition(), Id.STR_FILE);
                                                }

                                            source.rewind();
                                            }
                                        break;

                                    case '/':
                                        // it is a file name
                                        source.rewind();
                                        return new Token(lInitPos, source.getPosition(), Id.STR_FILE);
                                    }

                                source.rewind();
                                }
                            break;
                        }

                    source.rewind();
                    }
                return new Token(lInitPos, source.getPosition(), Id.IDENTIFIER, "$");

            case '#':
                if (source.hasNext())
                    {
                    switch (nextChar())
                        {
                        case '.':
                        case '/':
                            // it is a file name
                            source.rewind();
                            return new Token(lInitPos, source.getPosition(), Id.BIN_FILE);

                        case '|':
                            return eatBinaryLiteral(lInitPos, true);

                        default:
                            // fall through (it will be an error)
                        case '0': case '1': case '2': case '3': case '4':
                        case '5': case '6': case '7': case '8': case '9':
                        case 'a': case 'b': case 'c': case 'd': case 'e': case 'f':
                        case 'A': case 'B': case 'C': case 'D': case 'E': case 'F':
                            source.rewind();
                            // fall through
                        }
                    }
                return eatBinaryLiteral(lInitPos, false);

            case '@':
                return new Token(lInitPos, source.getPosition(), Id.AT);

            case '?':
                if (source.hasNext())
                    {
                    switch (nextChar())
                        {
                        case '=':
                            return new Token(lInitPos, source.getPosition(), Id.COND_NN_ASN);

                        case ':':
                            if (source.hasNext())
                                {
                                if (nextChar() == '=')
                                    {
                                    return new Token(lInitPos, source.getPosition(), Id.COND_ELSE_ASN);
                                    }
                                source.rewind();
                                }
                            return new Token(lInitPos, source.getPosition(), Id.COND_ELSE);
                        }
                    source.rewind();
                    }
                return new Token(lInitPos, source.getPosition(), Id.COND);

            case ':':
                if (source.hasNext())
                    {
                    if (nextChar() == '=')
                        {
                        return new Token(lInitPos, source.getPosition(), Id.COND_ASN);
                        }
                    source.rewind();
                    }
                return new Token(lInitPos, source.getPosition(), Id.COLON);

            case '+':
                if (source.hasNext())
                    {
                    switch (nextChar())
                        {
                        case '+':
                            return new Token(lInitPos, source.getPosition(), Id.INC);

                        case '=':
                            return new Token(lInitPos, source.getPosition(), Id.ADD_ASN);
                        }
                    source.rewind();
                    }
                return new Token(lInitPos, source.getPosition(), Id.ADD);

            case '-':
                if (source.hasNext())
                    {
                    switch (nextChar())
                        {
                        case '-':
                            return new Token(lInitPos, source.getPosition(), Id.DEC);

                        case '>':
                            return new Token(lInitPos, source.getPosition(), Id.LAMBDA);

                        case '=':
                            return new Token(lInitPos, source.getPosition(), Id.SUB_ASN);
                        }
                    source.rewind();
                    }
                return new Token(lInitPos, source.getPosition(), Id.SUB);

            case '*':
                if (source.hasNext())
                    {
                    if (nextChar() == '=')
                        {
                        return new Token(lInitPos, source.getPosition(), Id.MUL_ASN);
                        }
                    source.rewind();
                    }
                return new Token(lInitPos, source.getPosition(), Id.MUL);

            case '/':
                if (source.hasNext())
                    {
                    switch (nextChar())
                        {
                        case '/':
                            return eatSingleLineComment(lInitPos);

                        case '*':
                            return eatEnclosedComment(lInitPos);

                        case '=':
                            return new Token(lInitPos, source.getPosition(), Id.DIV_ASN);

                        case '%':
                            return new Token(lInitPos, source.getPosition(), Id.DIVMOD);
                        }
                    source.rewind();
                    }
                return new Token(lInitPos, source.getPosition(), Id.DIV);

            case '<':
                if (source.hasNext())
                    {
                    switch (nextChar())
                        {
                        case '<':
                            if (source.hasNext())
                                {
                                if (nextChar() == '=')
                                    {
                                    return new Token(lInitPos, source.getPosition(), Id.SHL_ASN);
                                    }
                                source.rewind();
                                }
                            return new Token(lInitPos, source.getPosition(), Id.SHL);

                        case '=':
                            if (source.hasNext())
                                {
                                if (nextChar() == '>')
                                    {
                                    return new Token(lInitPos, source.getPosition(), Id.COMP_ORD);
                                    }
                                source.rewind();
                                }
                            return new Token(lInitPos, source.getPosition(), Id.COMP_LTEQ);
                        }
                    source.rewind();
                    }
                return new Token(lInitPos, source.getPosition(), Id.COMP_LT);

            case '>':
                if (source.hasNext())
                    {
                    switch (nextChar())
                        {
                        case '>':
                            if (source.hasNext())
                                {
                                switch (nextChar())
                                    {
                                    case '>':
                                        if (source.hasNext())
                                            {
                                            if (nextChar() == '=')
                                                {
                                                return new Token(lInitPos, source.getPosition(), Id.USHR_ASN);
                                                }
                                            source.rewind();
                                            }
                                        return new Token(lInitPos, source.getPosition(), Id.USHR);

                                    case '=':
                                        return new Token(lInitPos, source.getPosition(), Id.SHR_ASN);
                                    }
                                source.rewind();
                                }
                            return new Token(lInitPos, source.getPosition(), Id.SHR);

                        case '=':
                            return new Token(lInitPos, source.getPosition(), Id.COMP_GTEQ);
                        }
                    source.rewind();
                    }
                return new Token(lInitPos, source.getPosition(), Id.COMP_GT);

            case '&':
                if (source.hasNext())
                    {
                    switch (nextChar())
                        {
                        case '&':
                            if (source.hasNext())
                                {
                                if (nextChar() == '=')
                                    {
                                    return new Token(lInitPos, source.getPosition(), Id.COND_AND_ASN);
                                    }
                                source.rewind();
                                }
                            return new Token(lInitPos, source.getPosition(), Id.COND_AND);

                        case '=':
                            return new Token(lInitPos, source.getPosition(), Id.BIT_AND_ASN);
                        }
                    source.rewind();
                    }
                return new Token(lInitPos, source.getPosition(), Id.BIT_AND);

            case '|':
                if (source.hasNext())
                    {
                    switch (nextChar())
                        {
                        case '|':
                            if (source.hasNext())
                                {
                                if (nextChar() == '=')
                                    {
                                    return new Token(lInitPos, source.getPosition(), Id.COND_OR_ASN);
                                    }
                                source.rewind();
                                }
                            return new Token(lInitPos, source.getPosition(), Id.COND_OR);

                        case '=':
                            return new Token(lInitPos, source.getPosition(), Id.BIT_OR_ASN);
                        }
                    source.rewind();
                    }
                return new Token(lInitPos, source.getPosition(), Id.BIT_OR);

            case '=':
                if (source.hasNext())
                    {
                    if (nextChar() == '=')
                        {
                        return new Token(lInitPos, source.getPosition(), Id.COMP_EQ);
                        }
                    source.rewind();
                    }
                return new Token(lInitPos, source.getPosition(), Id.ASN);

            case '%':
                if (source.hasNext())
                    {
                    if (nextChar() == '=')
                        {
                        return new Token(lInitPos, source.getPosition(), Id.MOD_ASN);
                        }
                    source.rewind();
                    }
                return new Token(lInitPos, source.getPosition(), Id.MOD);

            case '!':
                if (source.hasNext())
                    {
                    if (nextChar() == '=')
                        {
                        return new Token(lInitPos, source.getPosition(), Id.COMP_NEQ);
                        }
                    source.rewind();
                    }
                return new Token(lInitPos, source.getPosition(), Id.NOT);

            case '^':
                if (source.hasNext())
                    {
                    switch (nextChar())
                        {
                        case '^':
                            return new Token(lInitPos, source.getPosition(), Id.COND_XOR);

                        case '=':
                            return new Token(lInitPos, source.getPosition(), Id.BIT_XOR_ASN);
                        }
                    source.rewind();
                    }
                return new Token(lInitPos, source.getPosition(), Id.BIT_XOR);

            case '~':
                return new Token(lInitPos, source.getPosition(), Id.BIT_NOT);

            case '0': case '1': case '2': case '3': case '4':
            case '5': case '6': case '7': case '8': case '9':
                source.rewind();
                return eatNumericLiteral();

            case '\'':
                return eatCharLiteral(lInitPos);

            case '\"':
                return eatStringLiteral(lInitPos);

            case '`':
                if (source.hasNext())
                    {
                    if (nextChar() == '|')
                        {
                        return eatMultilineLiteral(lInitPos);
                        }
                    source.rewind();
                    }
                // fall through

            default:
                if (!isIdentifierStart(chInit))
                    {
                    log(Severity.ERROR, ILLEGAL_CHAR, new Object[]{quotedChar(chInit)},
                            lInitPos, source.getPosition());
                    }
                // fall through
            case 'A':case 'B':case 'C':case 'D':case 'E':case 'F':case 'G':
            case 'H':case 'I':case 'J':case 'K':case 'L':case 'M':case 'N':
            case 'O':case 'P':case 'Q':case 'R':case 'S':case 'T':case 'U':
            case 'V':case 'W':case 'X':case 'Y':case 'Z':
            case 'a':case 'b':case 'c':case 'd':case 'e':case 'f':case 'g':
            case 'h':case 'i':case 'j':case 'k':case 'l':case 'm':case 'n':
            case 'o':case 'p':case 'q':case 'r':case 's':case 't':case 'u':
            case 'v':case 'w':case 'x':case 'y':case 'z':
            case '_':
                {
                while (source.hasNext())
                    {
                    if (!isIdentifierPart(nextChar()))
                        {
                        source.rewind();
                        break;
                        }
                    }

                long  lPos  = source.getPosition();
                String name = source.toString(lInitPos, lPos);
                if (source.hasNext())
                    {
                    char chNext = source.next();
                    if (name.equals(Id.TODO.TEXT))
                        {
                        source.rewind();
                        if (chNext == '(')
                            {
                            return new Token(lInitPos, source.getPosition(), Id.TODO, null);
                            }
                        else
                            {
                            // parse the to-do statement in the same manner as a single line comment
                            Token comment = eatSingleLineComment(lInitPos);
                            return new Token(comment.getStartPosition(), comment.getEndPosition(),
                                    Id.TODO, comment.getValue());
                            }
                        }
                    else if (chNext == ':')
                        {
                        if (Token.Id.valueByPrefix(name) != null)
                            {
                            // check for a legal suffix, e.g. "this:private"
                            while (source.hasNext())
                                {
                                if (!isIdentifierPart(nextChar()))
                                    {
                                    source.rewind();
                                    break;
                                    }
                                }

                            String full = source.toString(lInitPos, source.getPosition());
                            if (Id.valueByContextSensitiveText(full) != null)
                                {
                                name = full;
                                }
                            else
                                {
                                // false alarm; back up and just take "this"
                                source.setPosition(lPos);
                                }
                            }
                        else
                            {
                            // check for suffix of private / protected / public / struct (etc.)
                            long lPosSuffix = source.getPosition();
                            while (source.hasNext())
                                {
                                if (!isIdentifierPart(nextChar()))
                                    {
                                    source.rewind();
                                    break;
                                    }
                                }
                            String suffix = source.toString(lPosSuffix, source.getPosition());
                            source.setPosition(lPosSuffix); // back up past the suffix

                            if (Id.valueByText(suffix) == null)
                                {
                                switch (name)
                                    {
                                    case "Date":
                                        return eatDate(lInitPos, false);
                                    case "Time":
                                        return eatTime(lInitPos, false);
                                    case "DateTime":
                                        return eatDateTime(lInitPos);
                                    case "TimeZone":
                                        return eatTimeZone(lInitPos);
                                    case "Duration":
                                        return eatDuration(lInitPos);
                                    case "Version":
                                    case "v":
                                        return eatVersion(lInitPos);

                                    default:
                                        source.rewind(); // back up past the ':'
                                    }
                                }
                            else
                                {
                                source.rewind(); // back up past the ':'
                                }
                            }
                        }
                    else
                        {
                        source.rewind();
                        }
                    }

                Id id = Id.valueByText(name);
                return new Token(lInitPos, source.getPosition(), id == null ? Id.IDENTIFIER : id, name);
                }
            }
        }

    /**
     * Eat a character literal.
     *
     * @return a character literal as a token
     */
    protected Token eatCharLiteral(long lInitPos)
        {
        final Source source   = m_source;
        final long   lPosChar = source.getPosition();

        char    ch   = '?';
        boolean term = false;
        if (source.hasNext())
            {
            switch (ch = source.next())
                {
                case '\'':
                    if (source.hasNext())
                        {
                        if (source.next() == '\'')
                            {
                            // assume the previous one should have been escaped
                            source.rewind();
                            log(Severity.ERROR, CHAR_BAD_ESC, null, lPosChar, source.getPosition());
                            }
                        else
                            {
                            // assume the encountered quote that we thought was supposed to be
                            // the character value was instead supposed to be closing quote
                            source.rewind();
                            source.rewind();
                            log(Severity.ERROR, CHAR_NO_CHAR, null, lPosChar, lPosChar);
                            }
                        }
                    break;

                case '\\':
                    // process escaped char
                    switch (ch = source.next())
                        {
                        case '\r':
                        case '\n':
                            // log error: newline in string
                            source.rewind();
                            log(Severity.ERROR, CHAR_NO_TERM, null, lInitPos, source.getPosition());
                            // assume it wasn't supposed to be an escape
                            ch = '\\';
                            break;

                        case '\\':
                        case '\'':
                        case '\"':
                            break;

                        case 'b':
                            ch = '\b';
                            break;
                        case 'f':
                            ch = '\f';
                            break;
                        case 'n':
                            ch = '\n';
                            break;
                        case 'r':
                            ch = '\r';
                            break;
                        case 't':
                            ch = '\t';
                            break;

                        default:
                            // log error: bad escape
                            log(Severity.ERROR, CHAR_BAD_ESC, null, lPosChar, source.getPosition());
                            break;
                        }
                    break;

                case '\r':
                case '\n':
                    // log error: newline in string
                    source.rewind();
                    log(Severity.ERROR, CHAR_NO_TERM, null, lInitPos, source.getPosition());
                    break;

                default:
                    break;
                }

            if (source.hasNext())
                {
                if (source.next() == '\'')
                    {
                    term = true;
                    }
                else
                    {
                    source.rewind();
                    }
                }
            }

        if (!term)
            {
            // log error: unterminated string
            log(Severity.ERROR, CHAR_NO_TERM, null, lInitPos, source.getPosition());
            }

        return new Token(lInitPos, source.getPosition(), Id.LIT_CHAR, new Character(ch));
        }

    /**
     * Eat a string literal.
     *
     * @return a string literal as a token
     */
    protected Token eatStringLiteral(long lInitPos)
        {
        return eatStringChars(lInitPos, false, false);
        }

    /**
     * Eat a string literal that is in the freeform/multi-line form.
     *
     * @return a string literal as a token
     */
    protected Token eatMultilineLiteral(long lInitPos)
        {
        return eatStringChars(lInitPos, false, true);
        }

    /**
     * Eat a template literal. A template literal is a string literal that may contain expressions;
     * for example:
     * <blockquote><code><pre>
     *   $"x={x}"
     * </pre></code></blockquote>
     *
     * @param lInitPos  the start of the template literal
     *
     * @return the resulting token
     */
    protected Token eatTemplateLiteral(long lInitPos)
        {
        return eatStringChars(lInitPos, true, false);
        }

    /**
     * Eat a template literal that uses the multi-line format. A template literal is a string
     * literal that may contain expressions; for example:
     * <blockquote><code><pre>
     *    $|# TOML doc
     *     |[name]
     *     |first = "{person.firstname}"
     *     |last = "{person.lastname}"
     * </pre></code></blockquote>
     *
     * @param lInitPos  the start of the template literal
     *
     * @return the resulting token
     */
    protected Token eatMultilineTemplateLiteral(long lInitPos)
        {
        return eatStringChars(lInitPos, true, true);
        }

    /**
     * Eat the character contents of a string literal, including the string literal portion(s) of a
     * template literal.
     *
     * @param lInitPos   the location of the start of the literal (the position of the {@code $} or
     *                   the {@code "})
     * @param fTemplate  true iff this is a template literal
     *
     * @return the resulting token
     */
    protected Token eatStringChars(long lInitPos, boolean fTemplate, boolean fMultiline)
        {
        final Source source = m_source;

        StringBuilder sb        = new StringBuilder();
        ArrayList     list      = fTemplate ? new ArrayList<>() : null;
        long          lPosStart = lInitPos;
        Appending: while (true)
            {
            if (source.hasNext())
                {
                char ch = source.next();
                switch (ch)
                    {
                    case '\"':
                        if (fMultiline)
                            {
                            sb.append(ch);
                            break;
                            }
                        break Appending;

                    case '\\':
                        if (fMultiline && !fTemplate)
                            {
                            sb.append(ch);
                            break;
                            }

                        // process escaped char
                        switch (ch = source.next())
                            {
                            case '\r':
                            case '\n':
                                // log error: newline in string
                                source.rewind();
                                log(Severity.ERROR, STRING_NO_TERM, null, lInitPos, source.getPosition());
                                // assume it wasn't supposed to be an escape
                                sb.append('\\');
                                break Appending;

                            case '\\':
                                sb.append('\\');
                                break;
                            case '\'':
                                sb.append('\'');
                                break;
                            case '\"':
                                sb.append('\"');
                                break;
                            case 'b':
                                sb.append('\b');
                                break;
                            case 'f':
                                sb.append('\f');
                                break;
                            case 'n':
                                sb.append('\n');
                                break;
                            case 'r':
                                sb.append('\r');
                                break;
                            case 't':
                                sb.append('\t');
                                break;

                            case '{':
                                if (fTemplate)
                                    {
                                    sb.append('{');
                                    break;
                                    }
                                // fall through

                            default:
                                // log error: bad escape
                                long lPosEscEnd = source.getPosition();
                                source.rewind();
                                source.rewind();
                                log(Severity.ERROR, STRING_BAD_ESC, null,
                                        source.getPosition(), lPosEscEnd);
                                source.setPosition(lPosEscEnd);

                                // assume it wasn't supposed to be an escape:
                                // append both the escape char and the escaped char
                                sb.append('\\')
                                  .append(ch);
                                break;
                            }
                        break;

                    case '\r':
                    case '\n':
                        if (fMultiline)
                            {
                            boolean fCRLF = false;
                            if (ch == '\r' && source.hasNext())
                                {
                                if (source.next() == '\n')
                                    {
                                    fCRLF = true;
                                    }
                                else
                                    {
                                    source.rewind();
                                    }
                                }

                            // eat whitespace and look for a continuation character
                            if (isMultilineContinued())
                                {
                                // it is a multi-line continuation, so include the newline that we
                                // just ate in the resulting text
                                if (fCRLF)
                                    {
                                    sb.append("\r\n");
                                    }
                                else
                                    {
                                    sb.append(ch);
                                    }
                                break;
                                }
                            else
                                {
                                // leave the newline in place (it's not part of the multiline)
                                source.rewind();
                                if (fCRLF)
                                    {
                                    source.rewind();
                                    }
                                }
                            }
                        else
                            {
                            // not a multi-line literal; log error: newline in string
                            source.rewind();
                            log(Severity.ERROR, STRING_NO_TERM, null, lInitPos, source.getPosition());
                            }
                        break Appending;

                    case '{':
                        if (fTemplate)
                            {
                            source.rewind();
                            long lPosCur = source.getPosition();
                            if (sb.length() > 0)
                                {
                                list.add(new Token(lPosStart, lPosCur, Id.LIT_STRING, sb.toString()));
                                sb = new StringBuilder();
                                }

                            // eat from the opening { to the closing } (inclusive of both)
                            list.add(eatTemplateExpression());

                            // start eating a new string portion of the template from this point
                            lPosStart = source.getPosition();
                            break;
                            }
                        // fall through

                    default:
                        sb.append(ch);
                        break;
                    }
                }
            else
                {
                // log error: unterminated string
                log(Severity.ERROR, STRING_NO_TERM, null, lInitPos, source.getPosition());
                }
            }

        if (fTemplate)
            {
            long lPosCur = source.getPosition();
            if (sb.length() > 0)
                {
                list.add(new Token(lPosStart, lPosCur, Id.LIT_STRING, sb.toString()));
                }
            else if (list.isEmpty())
                {
                return new Token(lPosStart, lPosCur, Id.LIT_STRING, "");
                }
            return new Token(lInitPos, lPosCur, Id.TEMPLATE, list.toArray());
            }

        return new Token(lPosStart, source.getPosition(), Id.LIT_STRING, sb.toString());
        }

    protected boolean isMultilineContinued()
        {
        long   lPrev  = getPosition();
        Source source = m_source;
        while (m_source.hasNext())
            {
            char ch = source.next();
            if (!isWhitespace(ch))
                {
                if (ch == '|')
                    {
                    return true;
                    }
                break;
                }
            }

        setPosition(lPrev);
        return false;
        }

    /**
     * Eat an inline template expression that begins with a '{' and ends with a '}'. The contents
     * <b>between</b> the opening and closing curlies are returned as an array of tokens.
     *
     * @return the tokens found between the opening and closing curly braces
     */
    protected Token[] eatTemplateExpression()
        {
        expect('{');
        int              cDepth = 1;
        ArrayList<Token> tokens = new ArrayList<>();
        while (true)
            {
            Token token = next();
            switch (token.getId())
                {
                case L_CURLY:
                    ++cDepth;
                    break;

                case R_CURLY:
                    if (--cDepth <= 0)
                        {
                        if (token.hasTrailingWhitespace())
                            {
                            // don't steal the whitespace; we're inside a literal!
                            m_source.setPosition(token.getEndPosition());
                            }

                        return tokens.toArray(new Token[0]);
                        }
                    break;
                }
            tokens.add(token);
            }
        }

    /**
     * Parse the binary literal value.
     *
     * @param lInitPos    the location of the "#" at the start of the binary literal value
     * @param fMultiline  true iff the format is a multiline format
     *
     * @return the binary literal
     */
    protected Token eatBinaryLiteral(long lInitPos, boolean fMultiline)
        {
        StringBuilder sb     = new StringBuilder();
        Source        source = m_source;
        boolean       fFirst = true;
        while (source.hasNext())
            {
            char ch = source.next();
            if (isHexit(ch))
                {
                sb.append(ch);
                }
            else if (ch == '_')
                {
                if (fFirst)
                    {
                    // it's an error to start with an underscore
                    log(Severity.ERROR, ILLEGAL_HEX, new Object[] {String.valueOf(ch)},
                            lInitPos, source.getPosition());
                    }
                // ignore the _ (it's used for spacing within the literal)
                }
            else if (!fMultiline)
                {
                // the first non-hexit / non-underscore character indicates an end-of-value
                source.rewind();
                break;
                }
            else if (isWhitespace(ch))
                {
                // ignore whitespace, unless it's newline
                if ((ch == '\r' || ch == '\n') && !isMultilineContinued())
                    {
                    source.rewind();
                    break;
                    }
                }
            else
                {
                // error
                log(Severity.ERROR, ILLEGAL_HEX, new Object[] {String.valueOf(ch)},
                        lInitPos, source.getPosition());
                source.rewind();
                break;
                }

            fFirst = false;
            }

        return new Token(lInitPos, source.getPosition(), Id.LIT_BINSTR,
                toBinary(sb.toString().toCharArray()));
        }

    protected byte[] toBinary(char[] ach)
        {
        int    cch  = ach.length;
        int    ofch = 0;
        int    cb   = (cch + 1) / 2;
        int    ofb  = 0;
        byte[] ab   = new byte[cb];
        if ((cch & 0x1) != 0)
            {
            // odd number of characters means that the first nibble is a pre-pended zero
            ab[ofb++] = (byte) hexitValue(ach[ofch++]);
            }
        while (ofb < cb)
            {
            ab[ofb++] = (byte) ( (hexitValue(ach[ofch++]) << 4)
                                + hexitValue(ach[ofch++])       );
            }

        return ab;
        }

    /**
     * Eat a numeric literal.
     *
     * @return the numeric literal as a Token
     */
    protected Token eatNumericLiteral()
        {
        final Source source    = m_source;
        final long   lPosStart = source.getPosition();

        // eat the first part of the number (or the entire number, if it is an integer literal)
        int[] results = new int[2];
        PackedInteger piWhole = eatIntegerLiteral(results);
        int mantissaRadix = results[0];
        int signScalar    = results[1];

        // parse optional '.' + value
        PackedInteger piFraction = null;
        int           fractionalDigits = 0;
        if (source.hasNext())
            {
            if (source.next() == '.')
                {
                // could be ".."
                if (source.hasNext())
                    {
                    boolean fNotDecimal = source.next() == '.';
                    // whatever it was, spit it back out
                    source.rewind();

                    // if it's not "..", it could be something else, e.g. a method name on type Int
                    if (!fNotDecimal && !isNextCharDigit(mantissaRadix))
                        {
                        fNotDecimal = true;
                        }

                    if (fNotDecimal)
                        {
                        // spit back out the first dot
                        source.rewind();

                        return new Token(lPosStart, source.getPosition(), Id.LIT_INT, piWhole);
                        }
                    }

                piFraction = eatDigits(false, mantissaRadix, results);
                fractionalDigits = results[0];

                if (fractionalDigits == 0)
                    {
                    log(Severity.ERROR, ILLEGAL_NUMBER, null,
                            lPosStart, source.getPosition());
                    }
                }
            else
                {
                source.rewind();
                }
            }

        // parse optional exponent
        PackedInteger piExp = null;
        boolean mustBeBinary = false;
        if (source.hasNext())
            {
            char ch = source.next();
            switch (ch)
                {
                case 'E': case 'e':
                    piExp = eatIntegerLiteral(null);
                    break;

                case 'P': case 'p':
                    piExp = eatIntegerLiteral(null);
                    mustBeBinary = true;
                    break;

                default:
                    // anything else should be whitespace or some type of operator/separator
                    long lEndPos = source.getPosition();
                    source.rewind();
                    if (isIdentifierPart(ch))
                        {
                        log(Severity.ERROR, ILLEGAL_NUMBER, null,
                                source.getPosition(), lEndPos);
                        }
                    break;
                }
            }

        final long lPosEnd = source.getPosition();
        if (piFraction == null && piExp == null)
            {
            return new Token(lPosStart, lPosEnd, Id.LIT_INT, piWhole);
            }
        else if (!mustBeBinary && mantissaRadix == 10)
            {
            // convert to IEEE-754 decimal floating point format
            // note: for now it is simply stored in the literal token as a BigDecimal
            BigDecimal dec;
            if (piFraction == null)
                {
                dec = new BigDecimal(piWhole.getBigInteger());
                }
            else
                {
                BigInteger biWhole = piWhole.getBigInteger();
                if (biWhole.signum() < 0)
                    {
                    biWhole = biWhole.negate();
                    }
                dec = new BigDecimal(biWhole.multiply(BigInteger.valueOf(10 * fractionalDigits))
                        .add(piFraction.getBigInteger()), fractionalDigits);
                if (signScalar < 0)
                    {
                    // the unfortunate side-effect of not having a -0
                    dec = dec.negate();
                    }
                }
            if (piExp != null)
                {
                long lExp = piExp.getLong();
                if (lExp > 6144 || lExp < (1-6144))
                    {
                    log(Severity.ERROR, ILLEGAL_NUMBER, null,
                            lPosStart, lPosEnd);
                    }
                else
                    {
                    dec = dec.scaleByPowerOfTen((int) lExp);
                    }
                }
            return new Token(lPosStart, lPosEnd, Id.LIT_DEC, dec);
            }
        else
            {
            // convert to IEEE-754 binary floating point format
            // note: for now it is simply stored in the literal token as a String
            return new Token(lPosStart, lPosEnd, Id.LIT_BIN, source.toString(lPosStart, lPosEnd));
            }
        }

    /**
     * The next character must begin an integer literal. Parse it and return it as a PackedInteger.
     *
     * @return a PackedInteger
     */
    protected PackedInteger eatIntegerLiteral(int[] otherResults)
        {
        final Source source    = m_source;
        final long   lPosStart = source.getPosition();

        // the first character could be a sign (+ or -)
        boolean fNeg = false;
        char    ch   = needCharOrElse(ILLEGAL_NUMBER);
        if (ch == '+' || ch == '-')
            {
            fNeg = (ch == '-');
            ch   = needCharOrElse(ILLEGAL_NUMBER);
            }

        // if the next character is '0', it is potentially part of a prefix denoting a radix
        int radix = 10;
        if (ch == '0' && source.hasNext())
            {
            switch (nextChar())
                {
                case 'B':
                case 'b':
                    radix = 2;
                    break;
                case 'o':
                    radix = 8;
                    break;
                case 'X':
                case 'x':
                    radix = 16;
                    break;
                default:
                    source.rewind();
                    source.rewind();
                    break;
                }
            }
        else
            {
            source.rewind();
            }

        // don't you just wish that Java had multiple return values?
        if (otherResults != null)
            {
            if (otherResults.length > 0)
                {
                otherResults[0] = radix;
                }
            if (otherResults.length > 1)
                {
                otherResults[1] = fNeg ? -1 : 1;
                }
            }

        return eatDigits(fNeg, radix, null);
        }

    protected boolean isNextCharDigit(int radix)
        {
        boolean fDigit = false;

        final Source source = m_source;
        if (source.hasNext())
            {
            switch (nextChar())
                {
                case 'A': case 'B': case 'C': case 'D': case 'E': case 'F':
                case 'a': case 'b': case 'c': case 'd': case 'e': case 'f':
                    fDigit = radix >= 16;
                    break;

                case '9': case '8':
                    fDigit = radix >= 10;
                    break;

                case '7': case '6': case '5': case '4': case '3': case '2':
                    fDigit = radix >= 8;
                    break;

                case '1':
                case '0':
                    fDigit = true;
                    break;

                default:
                    break;
                }
            source.rewind();
            }

        return fDigit;
        }

    /**
     * The next character must begin a sequence of digits of the specified radix. Parse it and
     * return it as a PackedInteger.
     *
     * @return a PackedInteger
     */
    protected PackedInteger eatDigits(boolean fNeg, int radix, int[] digitCount)
        {
        long       lValue  = 0;
        BigInteger bigint  = null;   // just in case
        boolean    fError  = false;
        int        cDigits = 0;

        final Source source = m_source;
        final long lPosStart = source.getPosition();
        Parsing: while (source.hasNext())
            {
            final long lPos = source.getPosition();
            final char ch   = nextChar();
            switch (ch)
                {
                case 'A': case 'B': case 'C': case 'D': case 'E': case 'F':
                case 'a': case 'b': case 'c': case 'd': case 'e': case 'f':
                    if (radix < 16)
                        {
                        // "e" is used as the decimal exponent indicator
                        if (ch != 'E' && ch != 'e')
                            {
                            if (!fError)
                                {
                                log(Severity.ERROR, ILLEGAL_NUMBER, null,
                                        lPos, source.getPosition());
                                fError = true;
                                }
                            }
                        source.rewind();
                        break Parsing;
                        }
                    break;

                case '9': case '8':
                    if (radix < 10)
                        {
                        if (!fError)
                            {
                            log(Severity.ERROR, ILLEGAL_NUMBER, null,
                                    lPos, source.getPosition());
                            fError = true;
                            }
                        // while an error was encountered, it was at least a digit, so continue
                        // parsing those digits (even if they are bad)
                        continue Parsing;
                        }
                    break;

                case '7': case '6': case '5': case '4': case '3': case '2':
                    if (radix < 8)
                        {
                        if (!fError)
                            {
                            log(Severity.ERROR, ILLEGAL_NUMBER, null,
                                    lPos, source.getPosition());
                            fError = true;
                            }
                        // while an error was encountered, it was at least a digit, so continue
                        // parsing those digits (even if they are bad)
                        continue Parsing;
                        }
                    break;

                case '1': case '0':
                    break;

                case '_':
                    if (cDigits == 0 && !fError)
                        {
                        // it's an error to start the sequence of digits with an underscore
                        log(Severity.ERROR, ILLEGAL_NUMBER, null,
                                lPos, source.getPosition());
                        fError = true;
                        }
                    continue Parsing;

                default:
                    // anything else (including '.') means go to the next step
                    source.rewind();
                    break Parsing;
                }

            if (bigint == null)
                {
                lValue = lValue * radix + hexitValue(ch);
                if (lValue > 0x00FFFFFFFFFFFFFFL)
                    {
                    bigint = BigInteger.valueOf(fNeg ? -lValue : lValue);
                    }
                }
            else
                {
                bigint = bigint.multiply(BigInteger.valueOf(radix)).add(BigInteger.valueOf(hexitValue(ch)));
                }
            ++cDigits;
            }

        if (!fError && cDigits == 0)
            {
            log(Severity.ERROR, ILLEGAL_NUMBER, null,
                    lPosStart, source.getPosition());
            }

        if (digitCount != null && digitCount.length > 0)
            {
            digitCount[0] = cDigits;
            }
        return bigint == null ? new PackedInteger(fNeg ? -lValue : lValue) : new PackedInteger(bigint);
        }

    /**
     * Eat a specified number of decimal digits, and convert them to an integer.
     *
     * @param digitCount  the number of digits to eat
     *
     * @return the parsed integer value, or the negative thereof if an error was encountered
     */
    protected int eatDigits(int digitCount)
        {
        final Source source    = m_source;
        final long   lPosStart = source.getPosition();

        int n = 0;
        for (int i = 0; i < digitCount; ++i)
            {
            if (!isNextCharDigit(10))
                {
                log(Severity.ERROR, EXPECTED_DIGITS, new Object[] {digitCount, i},
                        lPosStart, source.getPosition());
                return -n;
                }

            n = n * 10 + (nextChar() - '0');
            }
        return n;
        }

    /**
     * @return the value, or a negative value if there was no number or the number overflowed
     */
    protected long eatUnsignedLong()
        {
        final Source source    = m_source;
        final long   lPosStart = source.getPosition();

        long n = 0;
        if (isNextCharDigit(10))
            {
            do
                {
                if (n < 0)
                    {
                    return n;
                    }

                n = n * 10 + (nextChar() - '0');
                }
            while (isNextCharDigit(10));

            return n;
            }
        else
            {
            return -1;
            }
        }

    /**
     * Eat a literal ISO-8601 date value.
     *
     * @param lInitPos    the location of the start of the literal token
     * @param fContinued  true if this is just part of a larger literal
     *
     * @return the literal as a Token
     */
    protected Token eatDate(long lInitPos, boolean fContinued)
        {
        final Source source  = m_source;
        final long   lLitPos = source.getPosition();

        int nYear  = 0;
        int nMonth = 0;
        int nDay   = 0;

        if ((nYear = eatDigits(4)) >= 0)
            {
            boolean fSep = match('-');
            if ((nMonth = eatDigits(2)) >= 0)
                {
                if (fSep)
                    {
                    expect('-');
                    }
                if ((nDay = eatDigits(2)) >= 0)
                    {
                    if (!fContinued)
                        {
                        peekNotIdentifierOrNumber();
                        }
                    }
                }
            }

        long   lEndPos = source.getPosition();
        String sDate   = source.toString(lLitPos, lEndPos);
        if (nYear >= 0 && nMonth >= 0 && nDay >= 0)
            {
            if (nYear < 1582 || nMonth < 1 || nMonth > 12 || nDay < 1 || nDay > 31)
                {
                log(Severity.ERROR, BAD_DATE, new Object[] {sDate}, lLitPos, lEndPos);
                }
            }

        return new Token(lInitPos, lEndPos, Id.LIT_DATE, sDate);
        }

    /**
     * Eat a literal ISO-8601 time value.
     *
     * @param lInitPos  the location of the start of the literal token
     * @param fContinued  true if this is just part of a larger literal
     *
     * @return the literal as a Token
     */
    protected Token eatTime(long lInitPos, boolean fContinued)
        {
        final Source source = m_source;
        final long   lStart = source.getPosition();

        int  nHour   = 0;
        int  nMin    = 0;
        int  nSec    = 0;
        long nPicos  = 0;
        int  cDigits = -1;

        if ((nHour = eatDigits(2)) >= 0)
            {
            boolean fSep = match(':');
            if ((nMin = eatDigits(2)) >= 0)
                {
                if (fSep && match(':') || !fSep && isNextCharDigit(10))
                    {
                    nSec = eatDigits(2);
                    if (match('.'))
                        {
                        if (isNextCharDigit(10))
                            {
                            cDigits = 0;
                            do
                                {
                                ++cDigits;
                                nPicos = nPicos * 10 + (nextChar() - '0');
                                }
                            while (isNextCharDigit(10));

                            // scale the integer value up to picos (trillionths)
                            for (int i = cDigits; i < 12; ++i)
                                {
                                nPicos *= 10;
                                }
                            }
                        }
                    }
                if (!fContinued)
                    {
                    peekNotIdentifierOrNumber();
                    }
                }
            }

        long   lEnd  = source.getPosition();
        String sTime = source.toString(lStart, lEnd);
        if (nHour >= 0 && nMin >= 0 && nSec >= 0)
            {
            if (!((nHour >= 0 && nHour <= 23 || nHour == 24 && nMin == 0 && nSec == 0)
                    && (nMin >= 0 && nMin <= 59)
                    && (nSec >= 0 && nSec <= 59 || nMin == 59 && nSec == 60)))
                {
                log(Severity.ERROR, BAD_TIME, new Object[] {sTime}, lStart, lEnd);
                }
            }

        return new Token(lInitPos, lEnd, Id.LIT_TIME, sTime);
        }

    /**
     * Eat a literal ISO-8601 datetime value.
     *
     * @param lInitPos  the location of the start of the literal token
     *
     * @return the literal as a Token
     */
    protected Token eatDateTime(long lInitPos)
        {
        Token tokDate = eatDate(lInitPos, true);
        if (!(match('t') || expect('T')))
            {
            log(Severity.ERROR, BAD_DATETIME, new Object[] {tokDate.getValue()},
                    tokDate.getStartPosition(), tokDate.getEndPosition());
            return tokDate;
            }

        Token tokTime = eatTime(lInitPos, true);
        long  lEndPos = tokTime.getEndPosition();

        String sDT = tokDate.getValue() + "T" + tokTime.getValue();
        if (match('Z') || match('z') || match('+') || match('-'))
            {
            m_source.rewind();
            Token tokZone = eatTimeZone(lInitPos);
            sDT    += tokZone.getValue();
            lEndPos = tokZone.getEndPosition();
            }

        return new Token(lInitPos, lEndPos, Id.LIT_DATETIME, sDT);
        }

    /**
     * Eat a literal ISO-8601 timezone value.
     *
     * @param lInitPos  the location of the start of the literal token
     *
     * @return the literal as a Token
     */
    protected Token eatTimeZone(long lInitPos)
        {
        if (match('Z') || match('z'))
            {
            peekNotIdentifierOrNumber();
            return new Token(lInitPos, m_source.getPosition(), Id.LIT_TIMEZONE, "Z");
            }

        long lStart = m_source.getPosition();
        int  nHour  = 0;
        int  nMin   = 0;
        if (match('-') || expect('+'))
            {
            if ((nHour = eatDigits(2)) >= 0)
                {
                if (match(':') || isNextCharDigit(10))
                    {
                    if ((nMin = eatDigits(2)) >= 0)
                        {
                        peekNotIdentifierOrNumber();
                        }
                    }
                else
                    {
                    peekNotIdentifierOrNumber();
                    }
                }
            }
        long   lEnd  = m_source.getPosition();
        String sZone = m_source.toString(lStart, lEnd);

        if (nHour >= 0 && nMin >= 0)
            {
            if (nHour > 16 || nMin > 59)
                {
                log(Severity.ERROR, BAD_TIMEZONE, new Object[] {sZone}, lStart, lEnd);
                }
            }

        return new Token(lInitPos, lEnd, Id.LIT_TIMEZONE, sZone);
        }

    /**
     * Eat a literal ISO-8601 duration value.
     *
     * @param lInitPos  the location of the start of the literal token
     *
     * @return the literal as a Token
     */
    protected Token eatDuration(long lInitPos)
        {
        final Source source = m_source;
        final long   lStart = source.getPosition();

        // Stages:
        //   Y, M, D, (T), H, M, S
        //   1  2  3   4   5  6  7 . 8

        boolean fErr = false;
        if (!match('P'))
            {
            match('p');
            }

        int nPrevStage = match('T') ? 4 : 0;
        Loop: while (true)
            {
            long lPos = source.getPosition();
            long lVal = eatUnsignedLong();
            if (lVal < 0)
                {
                source.setPosition(lPos);
                break;
                }

            char ch = nextChar();
            switch (ch)
                {
                case 'Y':
                case 'y':
                    if (nPrevStage >= 1)
                        {
                        fErr = true;
                        }
                    nPrevStage = Math.max(1, nPrevStage);
                    break;

                case 'M':
                case 'm':
                    if (nPrevStage >= 2)
                        {
                        // parse as minute
                        if (nPrevStage < 4)
                            {
                            fErr = true;
                            }
                        nPrevStage = Math.max(6, nPrevStage);
                        }
                    else
                        {
                        // parse as month
                        nPrevStage = Math.max(2, nPrevStage);
                        }
                    break;

                case 'D':
                case 'd':
                    if (nPrevStage >= 3)
                        {
                        fErr = true;
                        }
                    nPrevStage = Math.max(3, nPrevStage);
                    break;

                case 'H':
                case 'h':
                    if (nPrevStage >= 5)
                        {
                        fErr = true;
                        }
                    nPrevStage = Math.max(5, nPrevStage);
                    break;

                case 'S':
                case 's':
                    nPrevStage = 8;
                    break Loop;

                case '.':
                    if (nPrevStage >= 7)
                        {
                        fErr = true;
                        }
                    nPrevStage = Math.max(7, nPrevStage);
                    break;

                default:
                    source.rewind();
                    fErr = true;
                    break;
                }

            if (match('T') || match('t'))
                {
                if (nPrevStage >= 4)
                    {
                    fErr = true;
                    }
                else
                    {
                    nPrevStage = 4;
                    }
                }
            }

        long   lEnd      = source.getPosition();
        String sDuration = source.toString(lStart, lEnd).toUpperCase();

        if (fErr)
            {
            log(Severity.ERROR, BAD_DURATION, new Object[] {sDuration}, lStart, lEnd);
            }
        else
            {
            peekNotIdentifierOrNumber();
            }

        return new Token(lStart, lEnd, Id.LIT_DURATION, sDuration);
        }

    /**
     * Eat a literal version value.
     *
     * <p/><code><pre>
     * VersionString
     *     NonGASuffix
     *     VersionNumbers VersionFinish-opt
     *
     * VersionNumbers
     *     DigitsNoUnderscores
     *     VersionNumbers "." DigitsNoUnderscores
     *
     * VersionFinish:
     *      "." NonGASuffix
     *
     * NonGASuffix
     *       NonGAPrefix DigitsNoUnderscores-opt
     *
     * NonGAPrefix:
     *     "dev"           # developer build (default compiler stamp)
     *     "ci"            # continuous integration build (automated build, automated test)
     *     "qc"            # build selected for internal Quality Control
     *     "alpha"         # build selected for external alpha test (pre-release)
     *     "beta"          # build selected for external beta test (pre-release)
     *     "rc"            # build selected as a release candidate (pre-release; GA pending)
     * </pre></code>
     *
     * @param lInitPos  the location of the start of the literal token
     *
     * @return the literal as a Token
     */
    protected Token eatVersion(long lInitPos)
        {
        Source             source    = m_source;
        long               lVerPos   = source.getPosition();
        ArrayList<Integer> listParts = new ArrayList<>();

        // eat the VersionNumbers
        boolean fNeed = true;
        boolean fErr  = false;
        while (isNextCharDigit(10))
            {
            long lNum = eatUnsignedLong();
            if (lNum < 0 || lNum > Integer.MAX_VALUE)
                {
                if (!fErr)
                    {
                    log(Severity.ERROR, Parser.BAD_VERSION, null, lInitPos, getPosition());
                    fErr = true;
                    }
                lNum = 0;
                }

            listParts.add((int) lNum);

            if (match('.'))
                {
                fNeed = true;
                continue;
                }

            if (match('-'))
                {
                fNeed = true;
                break;
                }

            fNeed = false;
            }

        boolean fNonGA = true;
        char    ch     = nextChar();
        source.rewind();
        switch (ch)
            {
            case 'A':
            case 'a':
                listParts.add(-3);
                fErr = !expectCaseInsens("alpha");
                break;

            case 'B':
            case 'b':
                listParts.add(-2);
                fErr = !expectCaseInsens("beta");
                break;

            case 'C':
            case 'c':
                listParts.add(-6);
                fErr = !expectCaseInsens("ci");
                break;

            case 'D':
            case 'd':
                listParts.add(-5);
                fErr = !expectCaseInsens("dev");
                break;

            case 'Q':
            case 'q':
                listParts.add(-4);
                fErr = !expectCaseInsens("qa");
                break;

            case 'R':
            case 'r':
                listParts.add(-1);
                fErr = !expectCaseInsens("rc");
                break;

            default:
                fNonGA = false;
                if (fNeed)
                    {
                    log(Severity.ERROR, Parser.BAD_VERSION, null, lInitPos, getPosition());
                    fErr = true;
                    }
                break;
            }

        if (!fErr && fNonGA)
            {
            fNeed = match('.') || match('-');
            if (isNextCharDigit(10))
                {
                long lNum = eatUnsignedLong();
                if (lNum < 0 || lNum > Integer.MAX_VALUE)
                    {
                    fErr = true;
                    lNum = 0;
                    }
                listParts.add((int) lNum);
                }
            else
                {
                fErr = fNeed;
                }
            if (fErr)
                {
                log(Severity.ERROR, Parser.BAD_VERSION, null, lInitPos, getPosition());
                }
            }

        String sBuild = null;
        if (fErr)
            {
            // expurgate the remainder of the version string, whatever it is
            while (source.hasNext())
                {
                ch = nextChar();
                if (!(     ch >= 'A' && ch <= 'Z'
                        || ch >= 'a' && ch <= 'z'
                        || ch >= '0' && ch <= '9'
                        || ch == '+'
                        || ch == '-'
                        || ch == '.'))
                    {
                    source.rewind();
                    break;
                    }
                }
            }
        else if (match('+'))
            {
            long lPosBuild = source.getPosition();
            // skip over the build metadata
            while (source.hasNext())
                {
                ch = nextChar();
                if (!(     ch >= 'A' && ch <= 'Z'
                        || ch >= 'a' && ch <= 'z'
                        || ch >= '0' && ch <= '9'
                        || ch == '-'
                        || ch == '.'))
                    {
                    source.rewind();
                    break;
                    }
                }
            long lPosEnd = source.getPosition();
            if (lPosBuild == lPosEnd)
                {
                log(Severity.ERROR, Parser.BAD_VERSION, null, lInitPos, lPosEnd);
                }
            else
                {
                sBuild = source.toString(lPosBuild, lPosEnd);
                }
            }
        else
            {
            ch = nextChar();
            source.rewind();
            if (       ch >= 'A' && ch <= 'Z'
                    || ch >= 'a' && ch <= 'z'
                    || ch >= '0' && ch <= '9'
                    || ch == '-'
                    || ch == '.')
                {
                log(Severity.ERROR, Parser.BAD_VERSION, null, lInitPos, getPosition());
                fErr = true;
                }
            }

        Version ver;
        if (fErr)
            {
            ver = new Version("0");
            }
        else
            {
            int   c     = listParts.size();
            int[] parts = new int[c];
            for (int i = 0; i < c; ++i)
                {
                parts[i] = listParts.get(i);
                }
            ver = new Version(parts, sBuild);
            }

        return new Token(lInitPos, getPosition(), Id.LIT_VERSION, ver);
        }

    /**
     * Eat a single line aka end-of-line comment.
     *
     * @return the comment as a Token
     */
    protected Token eatSingleLineComment(long lPosTokenStart)
        {
        final Source source        = m_source;
        final long   lPosTextStart = source.getPosition();
        while (source.hasNext())
            {
            if (isLineTerminator(nextChar()))
                {
                source.rewind();
                break;
                }
            }
        final long lPosEnd = source.getPosition();
        return new Token(lPosTokenStart, lPosEnd, Id.EOL_COMMENT,
                source.toString(lPosTextStart, lPosEnd));
        }

    /**
     * Eat a multi-line aka enclosed comment.
     *
     * @return the comment as a Token
     */
    protected Token eatEnclosedComment(long lPosTokenStart)
        {
        final Source source        = m_source;
        final long   lPosTextStart = source.getPosition();

        boolean fAsterisk = false;
        while (source.hasNext())
            {
            final char chNext = nextChar();
            if (chNext == '*')
                {
                fAsterisk = true;
                }
            else if (fAsterisk && chNext == '/')
                {
                final long lPosTokenEnd = source.getPosition();
                source.rewind();
                source.rewind();
                final long lPosTextEnd  = source.getPosition();
                source.setPosition(lPosTokenEnd);
                return new Token(lPosTokenStart, lPosTokenEnd, Id.ENC_COMMENT,
                        source.toString(lPosTextStart, lPosTextEnd));
                }
            else
                {
                fAsterisk = false;
                }
            }

        // missing the enclosing "*/"
        log(Severity.ERROR, EXPECTED_ENDCOMMENT, null,
                lPosTextStart, source.getPosition());

        // just pretend that the rest of the file was all one big comment
        return new Token(lPosTokenStart, source.getPosition(), Id.ENC_COMMENT,
                source.toString(lPosTextStart, source.getPosition()));
        }

    /**
     * Obtain a cookie that represents the lexer's current location.
     *
     * @return a position cookie
     */
    public long getPosition()
        {
        // this adds a bit of information to the source's position info
        long lPos = m_source.getPosition();
        assert (lPos & (1L << 63)) == 0L;
        if (m_fWhitespace)
            {
            lPos |= (1L << 63);
            }
        return lPos;
        }

    /**
     * Using a previously returned position cookie, restore that state of the lexer.
     *
     * @param lPos  a previously returned position cookie
     */
    public void setPosition(long lPos)
        {
        m_fWhitespace = (lPos & (1L << 63)) != 0L;
        m_source.setPosition(lPos & ~(1L << 63));
        }

    /**
     * Obtain the character located at a previously returned position cookie, but the state of the
     * Lexer after this call must match that from before the call.
     *
     * @param lPos  a previously returned position cookie
     *
     * @return the character at the specified position
     */
    public char charAt(long lPos)
        {
        long lPrev = getPosition();
        setPosition(lPos);
        char ch = nextChar();
        setPosition(lPrev);
        return ch;
        }

    /**
     * Get the next character of source code, but do some additional checks
     * on the character to make sure it's legal, such as checking for an illegal
     * SUB character.
     */
    protected char nextChar()
        {
        final char ch = m_source.next();

        // it is illegal for the SUB aka EOF character to occur
        // anywhere in the source other than at the end
        if (ch == EOF && m_source.hasNext())
            {
            // back up to get the location of the SUB character
            final long lPos = m_source.getPosition();
            m_source.rewind();
            final long lStartPos = m_source.getPosition();
            m_source.setPosition(lPos);

            log(Severity.ERROR, UNEXPECTED_EOF, null,
                    lStartPos, m_source.getPosition());
            }

        return ch;
        }

    /**
     * Get the next character of source code, but do some additional checks
     * on the character to make sure it's legal, such as checking for an illegal
     * SUB character.
     */
    protected char needCharOrElse(String sError)
        {
        try
            {
            return nextChar();
            }
        catch (NoSuchElementException e)
            {
            log(Severity.ERROR, sError, null,
                    m_source.getPosition(), m_source.getPosition());
            }

        // already logged an error; just pretend we hit a closing brace (since all roads should have
        // gone there)
        return '}';
        }

    /**
     * Get the next character of source code, making sure that it is the specified character.
     *
     * @param ch  the desired character
     *
     * @return true iff the next character was matched
     */
    protected boolean match(char ch)
        {
        char chActual;
        try
            {
            chActual = nextChar();
            }
        catch (NoSuchElementException e)
            {
            log(Severity.ERROR, UNEXPECTED_EOF, null,
                    m_source.getPosition(), m_source.getPosition());
            return false;
            }

        if (chActual != ch)
            {
            m_source.rewind();
            return false;
            }

        return true;
        }

    /**
     * Get the next character of source code, making sure that it is the specified character.
     *
     * @param ch  the expected character
     *
     * @return true iff the next character was found
     */
    protected boolean expect(char ch)
        {
        char chActual;
        try
            {
            chActual = nextChar();
            }
        catch (NoSuchElementException e)
            {
            log(Severity.ERROR, UNEXPECTED_EOF, null,
                    m_source.getPosition(), m_source.getPosition());
            return false;
            }

        if (chActual != ch)
            {
            log(Severity.ERROR, EXPECTED_CHAR,
                    new Object[] {String.valueOf(ch), String.valueOf(chActual)},
                    m_source.getPosition(), m_source.getPosition());
            return false;
            }

        return true;
        }

    protected boolean expectCaseInsens(String s)
        {
        for (int i = 0, c = s.length(); i < c; ++i)
            {
            char ch = s.charAt(i);
            if (match(ch) || match(Character.toLowerCase(ch)) || match(Character.toUpperCase(ch)))
                {
                continue;
                }

            // this will cause an error to be logged
            expect(ch);
            return false;
            }

        long lNext = getPosition();
        char ch    = nextChar();
        if (ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z')
            {
            log(Severity.ERROR, UNEXPECTED_CHAR, new Object[]{ch}, lNext, getPosition());
            return false;
            }

        m_source.rewind();
        return true;
        }

    /**
     * Verify that the next character is NOT a number or an identifier char.
     */
    protected void peekNotIdentifierOrNumber()
        {
        char ch   = nextChar();
        long lEnd = m_source.getPosition();
        m_source.rewind();
        if (ch >= '0' && ch <= '9' || isIdentifierPart(ch))
            {
            log(Severity.ERROR, UNEXPECTED_CHAR, new Object[] {ch}, m_source.getPosition(), lEnd);
            }
        }

    /**
     * Log an error.
     *
     * @param severity
     * @param sCode
     * @param aoParam
     * @param lPosStart
     * @param lPosEnd
     */
    protected void log(Severity severity, String sCode, Object[] aoParam, long lPosStart, long lPosEnd)
        {
        if (m_errorListener.log(severity, sCode, aoParam, m_source, lPosStart, lPosEnd))
            {
            throw new CompilerException("error list is full: " + m_errorListener);
            }
        }


    // ----- helper methods ------------------------------------------------------------------------

    /**
     * Determine if the specified character is white-space.
     *
     * @param ch  the character to evaluate
     *
     * @return true iff the character is defined as an XTC <i>SpacingElement</i>
     */
    public static boolean isWhitespace(char ch)
        {
        // optimize for the ASCII range
        if (ch < 128)
            {
            // this handles the following cases:
            //   U+0009   9  HT   Horizontal Tab
            //   U+000A  10  LF   Line Feed
            //   U+000B  11  VT   Vertical Tab
            //   U+000C  12  FF   Form Feed
            //   U+000D  13  CR   Carriage Return
            //   U+001A  26  SUB  End-of-File, or “control-Z”
            //   U+001C  28  FS   File Separator
            //   U+001D  29  GS   Group Separator
            //   U+001E  30  RS   Record Separator
            //   U+001F  31  US   Unit Separator
            //   U+0020  32  SP   Space
            //                                               2               1      0
            //                                               0FEDCBA9876543210FEDCBA9
            return ch >= 9 && ch <= 32 && ((1 << (ch-9)) & 0b111110100000000000011111) != 0;
            }

        // this handles the following cases:
        //   U+0085    133  NEL     Next Line
        //   U+00A0    160  &nbsp;  Non-breaking space
        //   U+1680   5760          Ogham Space Mark
        //   U+2000   8192          En Quad
        //   U+2001   8193          Em Quad
        //   U+2002   8194          En Space
        //   U+2003   8195          Em Space
        //   U+2004   8196          Three-Per-Em Space
        //   U+2005   8197          Four-Per-Em Space
        //   U+2006   8198          Six-Per-Em Space
        //   U+2007   8199          Figure Space
        //   U+2008   8200          Punctuation Space
        //   U+2009   8201          Thin Space
        //   U+200A   8202          Hair Space
        //   U+2028   8232   LS     Line Separator
        //   U+2029   8233   PS     Paragraph Separator
        //   U+202F   8239          Narrow No-Break Space
        switch (ch)
            {
            case 0x0085:
            case 0x00A0:
            case 0x1680:
            case 0x2000:
            case 0x2001:
            case 0x2002:
            case 0x2003:
            case 0x2004:
            case 0x2005:
            case 0x2006:
            case 0x2007:
            case 0x2008:
            case 0x2009:
            case 0x200A:
            case 0x2028:
            case 0x2029:
            case 0x202F:
            case 0x205F:
            case 0x3000:
                return true;

            default:
                return false;
            }
        }

    /**
     * Determine if the specified character is a line terminator.
     *
     * @param ch  the character to evaluate
     *
     * @return true iff the character is defined as an XTC <i>LineTerminator</i>
     */
    public static boolean isLineTerminator(char ch)
        {
        // optimize for the ASCII range
        if (ch < 128)
            {
            // this handles the following cases:
            //   U+000A  10  LF   Line Feed
            //   U+000B  11  VT   Vertical Tab
            //   U+000C  12  FF   Form Feed
            //   U+000D  13  CR   Carriage Return
            return ch >= 10 && ch <= 13;
            }

        // this handles the following cases:
        //   U+0085    133   NEL    Next Line
        //   U+2028   8232   LS     Line Separator
        //   U+2029   8233   PS     Paragraph Separator
        return ch == 0x0085 | ch == 0x2028 | ch == 0x2029;
        }

    /**
     * Determine if the specified character can be used as the first character
     * of an identifier.
     *
     * @param ch  the character to evaluate
     *
     * @return true iff the specified character can be the start of an
     *         identifier
     */
    public static boolean isIdentifierStart(char ch)
        {
        return Character.isUnicodeIdentifierStart(ch) || ch == '_';
        }

    /**
     * Determine if the specified character can be part of an identifier.
     *
     * @param ch  the character to evaluate
     *
     * @return true iff the specified character can be part of an identifier
     */
    public static boolean isIdentifierPart(char ch)
        {
        return Character.isUnicodeIdentifierPart(ch) || ch == '_';
        }

    /**
     * Validate the specified identifier.
     *
     * @param sName  the identifier
     *
     * @return true iff the identifier is a lexically valid identifier
     */
    public static boolean isValidIdentifier(String sName)
        {
        if (sName == null)
            {
            return false;
            }

        final int cch = sName.length();
        if (cch == 0)
            {
            return false;
            }

        if (!isIdentifierStart(sName.charAt(0)))
            {
            return false;
            }

        for (int i = 1; i < cch; ++i)
            {
            if (!isIdentifierPart(sName.charAt(i)))
                {
                return false;
                }
            }

        // check if it is a reserved word
        if (Token.Id.valueByText(sName) != null)
            {
            return false;
            }

        return true;
        }

    /**
     * Validate the specified RFC1035 label.
     *
     * @param sName  the RFC1035 label
     *
     * @return true iff the name is a lexically valid RFC1035 label
     */
    public static boolean isValidRFC1035Label(String sName)
        {
        if (sName == null)
            {
            return false;
            }

        // internationalization support; see https://tools.ietf.org/html/rfc5894
        // convert the label to an ASCII label
        try
            {
            sName = IDN.toASCII(sName);
            }
        catch (IllegalArgumentException e)
            {
            return false;
            }

        final int cch = sName.length();
        if (cch == 0 || cch > 63)
            {
            return false;
            }

        if (!isAsciiLetter(sName.charAt(0)))
            {
            return false;
            }

        for (int i = 1; i < cch; ++i)
            {
            final char ch = sName.charAt(i);
            if (!(isAsciiLetter(ch) || isDigit(ch) || i < cch - 1 && ch == '-'))
                {
                return false;
                }
            }

        return true;
        }

    /**
     * Validate the specified module name.
     *
     * @param sName  the module name
     *
     * @return true iff the name is a lexically valid qualified module name
     */
    public static boolean isValidQualifiedModule(String sName)
        {
        if (sName == null)
            {
            return false;
            }

        final String[] asName = parseDelimitedString(sName, '.');
        final int      cNames = asName.length;
        if (cNames < 1)
            {
            return false;
            }

        // the first simple name must be a valid identifier
        if (!isValidIdentifier(asName[0]))
            {
            return false;
            }

        // the identifier must be followed by a domain name, which is composed of at least two
        // RFC1035 labels
        if (cNames == 2)
            {
            return false;
            }

        // check the optional domain name
        for (int i = 1; i < cNames; ++i)
            {
            if (!isValidRFC1035Label(asName[i]))
                {
                return false;
                }
            }

        return true;
        }


    // ----- constants ---------------------------------------------------------

    /**
     * Unicode: Horizontal Tab.
     */
    public static final char HT  = 0x0009;
    /**
     * Unicode: Line Feed.
     */
    public static final char LF  = 0x000A;
    /**
     * Unicode: Vertical Tab.
     */
    public static final char VT  = 0x000B;
    /**
     * Unicode: Form Feed.
     */
    public static final char FF  = 0x000C;
    /**
     * Unicode: Carriage Return.
     */
    public static final char CR  = 0x000D;
    /**
     * Unicode: End-Of-File aka control-z aka SUB.
     */
    public static final char EOF = 0x001A;
    /**
     * Unicode: File Separator.
     */
    public static final char FS  = 0x001C;
    /**
     * Unicode: Group Separator.
     */
    public static final char GS  = 0x001D;
    /**
     * Unicode: Record Separator.
     */
    public static final char RS  = 0x001E;
    /**
     * Unicode: Unit Separator.
     */
    public static final char US  = 0x001F;
    /**
     * Unicode: Next Line.
     */
    public static final char NEL = 0x0085;
    /**
     * Unicode: Non-Breaking Space aka "&nbsp".
     */
    public static final char NBS = 0x00A0;
    /**
     * Unicode: Line Separator.
     */
    public static final char LS  = 0x2028;
    /**
     * Unicode: Paragraph Separator.
     */
    public static final char PS  = 0x2029;

    /**
     * Unexpected End-Of-File (SUB character).
     */
    public static final String UNEXPECTED_EOF       = "LEXER-01";
    /**
     * Expected a comment-ending "star slash" but never found one.
     */
    public static final String EXPECTED_ENDCOMMENT  = "LEXER-02";
    /**
     * A character was encountered that cannot be the start of a valid token.
     */
    public static final String ILLEGAL_CHAR         = "LEXER-03";
    /**
     * Number format exception.
     */
    public static final String ILLEGAL_NUMBER       = "LEXER-04";
    /**
     * An illegal character literal, missing closing quote.
     */
    public static final String CHAR_NO_TERM         = "LEXER-05";
    /**
     * An illegally escaped character literal.
     */
    public static final String CHAR_BAD_ESC         = "LEXER-06";
    /**
     * An illegal character literal missing the character.
     */
    public static final String CHAR_NO_CHAR         = "LEXER-07";
    /**
     * An illegally terminated string literal.
     */
    public static final String STRING_NO_TERM       = "LEXER-08";
    /**
     * An illegally escaped string literal.
     */
    public static final String STRING_BAD_ESC       = "LEXER-09";
    /**
     * An illegal hex (binary) literal.
     */
    public static final String ILLEGAL_HEX          = "LEXER-10";
    /**
     * Expected {0}; found {1}.
     */
    public static final String EXPECTED_CHAR        = "LEXER-11";
    /**
     * {0} digits were required; only {1} digits were found.
     */
    public static final String EXPECTED_DIGITS      = "LEXER-12";
    /**
     * Invalid ISO-8601 date {0}; ...
     */
    public static final String BAD_DATE             = "LEXER-13";
    /**
     * Invalid ISO-8601 time {0}; ...
     */
    public static final String BAD_TIME             = "LEXER-14";
    /**
     * Invalid ISO-8601 datetime {0}; ...
     */
    public static final String BAD_DATETIME         = "LEXER-15";
    /**
     * Invalid ISO-8601 timezone {0}; ...
     */
    public static final String BAD_TIMEZONE         = "LEXER-16";
    /**
     * Invalid ISO-8601 duration {0}; ...
     */
    public static final String BAD_DURATION         = "LEXER-17";
    /**
     * Unexpected character: {0}
     */
    public static final String UNEXPECTED_CHAR      = "LEXER-18";


    // ----- data members ------------------------------------------------------

    /**
     * The Source to parse.
     */
    private Source m_source;

    /**
     * The ErrorListener to report errors to.
     */
    private ErrorListener m_errorListener;

    /**
     * Keeps track of whether whitespace was encountered.
     */
    private boolean m_fWhitespace;
    }
