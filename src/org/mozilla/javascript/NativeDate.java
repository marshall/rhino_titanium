/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*-
 *
 * The contents of this file are subject to the Netscape Public
 * License Version 1.1 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of
 * the License at http://www.mozilla.org/NPL/
 *
 * Software distributed under the License is distributed on an "AS
 * IS" basis, WITHOUT WARRANTY OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * rights and limitations under the License.
 *
 * The Original Code is Rhino code, released
 * May 6, 1999.
 *
 * The Initial Developer of the Original Code is Netscape
 * Communications Corporation.  Portions created by Netscape are
 * Copyright (C) 1997-1999 Netscape Communications Corporation. All
 * Rights Reserved.
 *
 * Contributor(s):
 * Norris Boyd
 * Mike McCabe
 *
 * Alternatively, the contents of this file may be used under the
 * terms of the GNU Public License (the "GPL"), in which case the
 * provisions of the GPL are applicable instead of those above.
 * If you wish to allow use of your version of this file only
 * under the terms of the GPL and not to allow others to use your
 * version of this file under the NPL, indicate your decision by
 * deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL.  If you do not delete
 * the provisions above, a recipient may use your version of this
 * file under either the NPL or the GPL.
 */

package org.mozilla.javascript;

import java.util.Date;
import java.util.TimeZone;
import java.util.Locale;
import java.text.NumberFormat;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

/**
 * This class implements the Date native object.
 * See ECMA 15.9.
 * @author Mike McCabe
 */
final class NativeDate extends IdScriptable {

    static void init(Context cx, Scriptable scope, boolean sealed) {
        NativeDate obj = new NativeDate();
        obj.prototypeFlag = true;

        // Set the value of the prototype Date to NaN ('invalid date');
        obj.date = ScriptRuntime.NaN;

        obj.addAsPrototype(MAX_PROTOTYPE_ID, cx, scope, sealed);
    }

    private NativeDate() {
        if (thisTimeZone == null) {
            // j.u.TimeZone is synchronized, so setting class statics from it
            // should be OK.
            thisTimeZone = java.util.TimeZone.getDefault();
            LocalTZA = thisTimeZone.getRawOffset();
        }
    }

    public String getClassName() {
        return "Date";
    }

    public Object getDefaultValue(Class typeHint) {
        if (typeHint == null)
            typeHint = ScriptRuntime.StringClass;
        return super.getDefaultValue(typeHint);
    }

    double getJSTimeValue() {
        return date;
    }

    protected void fillConstructorProperties
        (Context cx, IdFunction ctor, boolean sealed)
    {
        addIdFunctionProperty(ctor, ConstructorId_UTC, sealed);
        addIdFunctionProperty(ctor, ConstructorId_parse, sealed);
        super.fillConstructorProperties(cx, ctor, sealed);
    }

    public int methodArity(int methodId) {
        if (prototypeFlag) {
            switch (methodId) {
                case ConstructorId_UTC:     return 1;
                case ConstructorId_parse:   return 1;
                case Id_constructor:        return 1;
                case Id_toString:           return 0;
                case Id_toTimeString:       return 0;
                case Id_toDateString:       return 0;
                case Id_toLocaleString:     return 0;
                case Id_toLocaleTimeString: return 0;
                case Id_toLocaleDateString: return 0;
                case Id_toUTCString:        return 0;
                case Id_toSource:           return 0;
                case Id_valueOf:            return 0;
                case Id_getTime:            return 0;
                case Id_getYear:            return 0;
                case Id_getFullYear:        return 0;
                case Id_getUTCFullYear:     return 0;
                case Id_getMonth:           return 0;
                case Id_getUTCMonth:        return 0;
                case Id_getDate:            return 0;
                case Id_getUTCDate:         return 0;
                case Id_getDay:             return 0;
                case Id_getUTCDay:          return 0;
                case Id_getHours:           return 0;
                case Id_getUTCHours:        return 0;
                case Id_getMinutes:         return 0;
                case Id_getUTCMinutes:      return 0;
                case Id_getSeconds:         return 0;
                case Id_getUTCSeconds:      return 0;
                case Id_getMilliseconds:    return 0;
                case Id_getUTCMilliseconds: return 0;
                case Id_getTimezoneOffset:  return 0;
                case Id_setTime:            return 1;
                case Id_setMilliseconds:    return 1;
                case Id_setUTCMilliseconds: return 1;
                case Id_setSeconds:         return 2;
                case Id_setUTCSeconds:      return 2;
                case Id_setMinutes:         return 3;
                case Id_setUTCMinutes:      return 3;
                case Id_setHours:           return 4;
                case Id_setUTCHours:        return 4;
                case Id_setDate:            return 1;
                case Id_setUTCDate:         return 1;
                case Id_setMonth:           return 2;
                case Id_setUTCMonth:        return 2;
                case Id_setFullYear:        return 3;
                case Id_setUTCFullYear:     return 3;
                case Id_setYear:            return 1;
            }
        }
        return super.methodArity(methodId);
    }

    public Object execMethod
        (int methodId, IdFunction f,
         Context cx, Scriptable scope, Scriptable thisObj, Object[] args)
        throws JavaScriptException
    {
        if (prototypeFlag) {
            switch (methodId) {
                case ConstructorId_UTC:
                    return wrap_double(jsStaticFunction_UTC(args));

                case ConstructorId_parse:
                    return wrap_double(date_parseString
                        (ScriptRuntime.toString(args, 0)));

                case Id_constructor: {
                    // if called as a function, just return a string
                    // representing the current time.
                    if (thisObj != null)
                        return date_format(Now(), Id_toString);
                    return jsConstructor(args);
                }

                case Id_toString:
                case Id_toTimeString:
                case Id_toDateString: {
                    double t = realThis(thisObj, f).date;
                    return date_format(t, methodId);
                }

                case Id_toLocaleString:
                case Id_toLocaleTimeString:
                case Id_toLocaleDateString: {
                    double t = realThis(thisObj, f).date;
                    return toLocale_helper(t, methodId);
                }

                case Id_toUTCString: {
                    double t = realThis(thisObj, f).date;
                    if (t == t) { return js_toUTCString(t); }
                    return js_NaN_date_str;
                }

                case Id_toSource: {
                    double t = realThis(thisObj, f).date;
                    return "(new Date("+ScriptRuntime.toString(t)+"))";
                }

                case Id_valueOf:
                case Id_getTime:
                    return wrap_double(realThis(thisObj, f).date);

                case Id_getYear:
                case Id_getFullYear:
                case Id_getUTCFullYear: {
                    double t = realThis(thisObj, f).date;
                    if (t == t) {
                        if (methodId != Id_getUTCFullYear)
                            t = LocalTime(t);
                        t = YearFromTime(t);
                        if (methodId == Id_getYear) {
                            if (cx.hasFeature(
                                    Context.FEATURE_NON_ECMA_GET_YEAR))
                            {
                                if (1900 <= t && t < 2000) {
                                    t -= 1900;
                                }
                            } else {
                                t -= 1900;
                            }
                        }
                    }
                    return wrap_double(t);
                }

                case Id_getMonth:
                case Id_getUTCMonth: {
                    double t = realThis(thisObj, f).date;
                    if (t == t) {
                        if (methodId == Id_getMonth)
                            t = LocalTime(t);
                        t = MonthFromTime(t);
                    }
                    return wrap_double(t);
                }

                case Id_getDate:
                case Id_getUTCDate: {
                    double t = realThis(thisObj, f).date;
                    if (t == t) {
                        if (methodId == Id_getDate)
                            t = LocalTime(t);
                        t = DateFromTime(t);
                    }
                    return wrap_double(t);
                }

                case Id_getDay:
                case Id_getUTCDay: {
                    double t = realThis(thisObj, f).date;
                    if (t == t) {
                        if (methodId == Id_getDay)
                            t = LocalTime(t);
                        t = WeekDay(t);
                    }
                    return wrap_double(t);
                }

                case Id_getHours:
                case Id_getUTCHours: {
                    double t = realThis(thisObj, f).date;
                    if (t == t) {
                        if (methodId == Id_getHours)
                            t = LocalTime(t);
                        t = HourFromTime(t);
                    }
                    return wrap_double(t);
                }

                case Id_getMinutes:
                case Id_getUTCMinutes: {
                    double t = realThis(thisObj, f).date;
                    if (t == t) {
                        if (methodId == Id_getMinutes)
                            t = LocalTime(t);
                        t = MinFromTime(t);
                    }
                    return wrap_double(t);
                }

                case Id_getSeconds:
                case Id_getUTCSeconds: {
                    double t = realThis(thisObj, f).date;
                    if (t == t) {
                        if (methodId == Id_getSeconds)
                            t = LocalTime(t);
                        t = SecFromTime(t);
                    }
                    return wrap_double(t);
                }

                case Id_getMilliseconds:
                case Id_getUTCMilliseconds: {
                    double t = realThis(thisObj, f).date;
                    if (t == t) {
                        if (methodId == Id_getMilliseconds)
                            t = LocalTime(t);
                        t = msFromTime(t);
                    }
                    return wrap_double(t);
                }

                case Id_getTimezoneOffset: {
                    double t = realThis(thisObj, f).date;
                    if (t == t) {
                        t = (t - LocalTime(t)) / msPerMinute;
                    }
                    return wrap_double(t);
                }

                case Id_setTime: {
                    NativeDate real = realThis(thisObj, f);
                    double t = ScriptRuntime.toNumber(args, 0);
                    real.date = TimeClip(t);
                    return wrap_double(real.date);
                }

                case Id_setMilliseconds:
                case Id_setUTCMilliseconds:
                case Id_setSeconds:
                case Id_setUTCSeconds:
                case Id_setMinutes:
                case Id_setUTCMinutes:
                case Id_setHours:
                case Id_setUTCHours:
                    return wrap_double(
                        realThis(thisObj, f).makeTime(args, methodId));

                case Id_setDate:
                case Id_setUTCDate:
                case Id_setMonth:
                case Id_setUTCMonth:
                case Id_setFullYear:
                case Id_setUTCFullYear:
                    return wrap_double(
                        realThis(thisObj, f).makeDate(args, methodId));

                case Id_setYear:
                    return wrap_double(realThis(thisObj, f).
                        js_setYear(ScriptRuntime.toNumber(args, 0)));
            }
        }

        return super.execMethod(methodId, f, cx, scope, thisObj, args);
    }

    private static NativeDate realThis(Scriptable thisObj, IdFunction f)
    {
        if (!(thisObj instanceof NativeDate))
            throw incompatibleCallError(f);
        return (NativeDate)thisObj;
    }

    /* ECMA helper functions */

    private static final double HalfTimeDomain = 8.64e15;
    private static final double HoursPerDay    = 24.0;
    private static final double MinutesPerHour = 60.0;
    private static final double SecondsPerMinute = 60.0;
    private static final double msPerSecond    = 1000.0;
    private static final double MinutesPerDay  = (HoursPerDay * MinutesPerHour);
    private static final double SecondsPerDay  = (MinutesPerDay * SecondsPerMinute);
    private static final double SecondsPerHour = (MinutesPerHour * SecondsPerMinute);
    private static final double msPerDay       = (SecondsPerDay * msPerSecond);
    private static final double msPerHour      = (SecondsPerHour * msPerSecond);
    private static final double msPerMinute    = (SecondsPerMinute * msPerSecond);

    private static double Day(double t) {
        return Math.floor(t / msPerDay);
    }

    private static double TimeWithinDay(double t) {
        double result;
        result = t % msPerDay;
        if (result < 0)
            result += msPerDay;
        return result;
    }

    private static int DaysInYear(int y) {
        if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0))
            return 366;
        else
            return 365;
    }


    /* math here has to be f.p, because we need
     *  floor((1968 - 1969) / 4) == -1
     */
    private static double DayFromYear(double y) {
        return ((365 * ((y)-1970) + Math.floor(((y)-1969)/4.0)
                 - Math.floor(((y)-1901)/100.0) + Math.floor(((y)-1601)/400.0)));
    }

    private static double TimeFromYear(double y) {
        return DayFromYear(y) * msPerDay;
    }

    private static int YearFromTime(double t) {
        int lo = (int) Math.floor((t / msPerDay) / 366) + 1970;
        int hi = (int) Math.floor((t / msPerDay) / 365) + 1970;
        int mid;

        /* above doesn't work for negative dates... */
        if (hi < lo) {
            int temp = lo;
            lo = hi;
            hi = temp;
        }

        /* Use a simple binary search algorithm to find the right
           year.  This seems like brute force... but the computation
           of hi and lo years above lands within one year of the
           correct answer for years within a thousand years of
           1970; the loop below only requires six iterations
           for year 270000. */
        while (hi > lo) {
            mid = (hi + lo) / 2;
            if (TimeFromYear(mid) > t) {
                hi = mid - 1;
            } else {
                lo = mid + 1;
                if (TimeFromYear(lo) > t) {
                    return mid;
                }
            }
        }
        return lo;
    }

    private static boolean InLeapYear(double t) {
        return DaysInYear(YearFromTime(t)) == 366;
    }

    private static int DayWithinYear(double t) {
        int year = YearFromTime(t);
        return (int) (Day(t) - DayFromYear(year));
    }
    /*
     * The following array contains the day of year for the first day of
     * each month, where index 0 is January, and day 0 is January 1.
     */

    private static double DayFromMonth(int m, boolean leap) {
        int day = m * 30;

        if (m >= 7) { day += m / 2 - 1; }
        else if (m >= 2) { day += (m - 1) / 2 - 1; }
        else { day += m; }

        if (leap && m >= 2) { ++day; }

        return day;
    }

    private static int MonthFromTime(double t) {
        int d, step;

        d = DayWithinYear(t);

        if (d < (step = 31))
            return 0;

        // Originally coded as step += (InLeapYear(t) ? 29 : 28);
        // but some jits always returned 28!
        if (InLeapYear(t))
            step += 29;
        else
            step += 28;

        if (d < step)
            return 1;
        if (d < (step += 31))
            return 2;
        if (d < (step += 30))
            return 3;
        if (d < (step += 31))
            return 4;
        if (d < (step += 30))
            return 5;
        if (d < (step += 31))
            return 6;
        if (d < (step += 31))
            return 7;
        if (d < (step += 30))
            return 8;
        if (d < (step += 31))
            return 9;
        if (d < (step += 30))
            return 10;
        return 11;
    }

    private static int DateFromTime(double t) {
        int d, step, next;

        d = DayWithinYear(t);
        if (d <= (next = 30))
            return d + 1;
        step = next;

        // Originally coded as next += (InLeapYear(t) ? 29 : 28);
        // but some jits always returned 28!
        if (InLeapYear(t))
            next += 29;
        else
            next += 28;

        if (d <= next)
            return d - step;
        step = next;
        if (d <= (next += 31))
            return d - step;
        step = next;
        if (d <= (next += 30))
            return d - step;
        step = next;
        if (d <= (next += 31))
            return d - step;
        step = next;
        if (d <= (next += 30))
            return d - step;
        step = next;
        if (d <= (next += 31))
            return d - step;
        step = next;
        if (d <= (next += 31))
            return d - step;
        step = next;
        if (d <= (next += 30))
            return d - step;
        step = next;
        if (d <= (next += 31))
            return d - step;
        step = next;
        if (d <= (next += 30))
            return d - step;
        step = next;

        return d - step;
    }

    private static int WeekDay(double t) {
        double result;
        result = Day(t) + 4;
        result = result % 7;
        if (result < 0)
            result += 7;
        return (int) result;
    }

    private static double Now() {
        return (double) System.currentTimeMillis();
    }

    /* Should be possible to determine the need for this dynamically
     * if we go with the workaround... I'm not using it now, because I
     * can't think of any clean way to make toLocaleString() and the
     * time zone (comment) in toString match the generated string
     * values.  Currently it's wrong-but-consistent in all but the
     * most recent betas of the JRE - seems to work in 1.1.7.
     */
    private final static boolean TZO_WORKAROUND = false;
    private static double DaylightSavingTA(double t) {
        if (!TZO_WORKAROUND) {
            Date date = new Date((long) t);
            if (thisTimeZone.inDaylightTime(date))
                return msPerHour;
            else
                return 0;
        } else {
            /* Use getOffset if inDaylightTime() is broken, because it
             * seems to work acceptably.  We don't switch over to it
             * entirely, because it requires (expensive) exploded date arguments,
             * and the api makes it impossible to handle dst
             * changeovers cleanly.
             */

            // Hardcode the assumption that the changeover always
            // happens at 2:00 AM:
            t += LocalTZA + (HourFromTime(t) <= 2 ? msPerHour : 0);

            int year = YearFromTime(t);
            double offset = thisTimeZone.getOffset(year > 0 ? 1 : 0,
                                                   year,
                                                   MonthFromTime(t),
                                                   DateFromTime(t),
                                                   WeekDay(t),
                                                   (int)TimeWithinDay(t));

            if ((offset - LocalTZA) != 0)
                return msPerHour;
            else
                return 0;
            //         return offset - LocalTZA;
        }
    }

    private static double LocalTime(double t) {
        return t + LocalTZA + DaylightSavingTA(t);
    }

    private static double internalUTC(double t) {
        return t - LocalTZA - DaylightSavingTA(t - LocalTZA);
    }

    private static int HourFromTime(double t) {
        double result;
        result = Math.floor(t / msPerHour) % HoursPerDay;
        if (result < 0)
            result += HoursPerDay;
        return (int) result;
    }

    private static int MinFromTime(double t) {
        double result;
        result = Math.floor(t / msPerMinute) % MinutesPerHour;
        if (result < 0)
            result += MinutesPerHour;
        return (int) result;
    }

    private static int SecFromTime(double t) {
        double result;
        result = Math.floor(t / msPerSecond) % SecondsPerMinute;
        if (result < 0)
            result += SecondsPerMinute;
        return (int) result;
    }

    private static int msFromTime(double t) {
        double result;
        result =  t % msPerSecond;
        if (result < 0)
            result += msPerSecond;
        return (int) result;
    }

    private static double MakeTime(double hour, double min,
                                   double sec, double ms)
    {
        return ((hour * MinutesPerHour + min) * SecondsPerMinute + sec)
            * msPerSecond + ms;
    }

    private static double MakeDay(double year, double month, double date) {
        double result;
        boolean leap;
        double yearday;
        double monthday;

        year += Math.floor(month / 12);

        month = month % 12;
        if (month < 0)
            month += 12;

        leap = (DaysInYear((int) year) == 366);

        yearday = Math.floor(TimeFromYear(year) / msPerDay);
        monthday = DayFromMonth((int) month, leap);

        result = yearday
            + monthday
            + date - 1;
        return result;
    }

    private static double MakeDate(double day, double time) {
        return day * msPerDay + time;
    }

    private static double TimeClip(double d) {
        if (d != d ||
            d == Double.POSITIVE_INFINITY ||
            d == Double.NEGATIVE_INFINITY ||
            Math.abs(d) > HalfTimeDomain)
        {
            return ScriptRuntime.NaN;
        }
        if (d > 0.0)
            return Math.floor(d + 0.);
        else
            return Math.ceil(d + 0.);
    }

    /* end of ECMA helper functions */

    /* find UTC time from given date... no 1900 correction! */
    private static double date_msecFromDate(double year, double mon,
                                            double mday, double hour,
                                            double min, double sec,
                                            double msec)
    {
        double day;
        double time;
        double result;

        day = MakeDay(year, mon, mday);
        time = MakeTime(hour, min, sec, msec);
        result = MakeDate(day, time);
        return result;
    }


    private static final int MAXARGS = 7;
    private static double jsStaticFunction_UTC(Object[] args) {
        double array[] = new double[MAXARGS];
        int loop;
        double d;

        for (loop = 0; loop < MAXARGS; loop++) {
            if (loop < args.length) {
                d = ScriptRuntime.toNumber(args[loop]);
                if (d != d || Double.isInfinite(d)) {
                    return ScriptRuntime.NaN;
                }
                array[loop] = ScriptRuntime.toInteger(args[loop]);
            } else {
                array[loop] = 0;
            }
        }

        /* adjust 2-digit years into the 20th century */
        if (array[0] >= 0 && array[0] <= 99)
            array[0] += 1900;

            /* if we got a 0 for 'date' (which is out of range)
             * pretend it's a 1.  (So Date.UTC(1972, 5) works) */
        if (array[2] < 1)
            array[2] = 1;

        d = date_msecFromDate(array[0], array[1], array[2],
                              array[3], array[4], array[5], array[6]);
        d = TimeClip(d);
        return d;
        //        return new Double(d);
    }

    private static double date_parseString(String s) {
        int year = -1;
        int mon = -1;
        int mday = -1;
        int hour = -1;
        int min = -1;
        int sec = -1;
        char c = 0;
        char si = 0;
        int i = 0;
        int n = -1;
        double tzoffset = -1;
        char prevc = 0;
        int limit = 0;
        boolean seenplusminus = false;

        limit = s.length();
        while (i < limit) {
            c = s.charAt(i);
            i++;
            if (c <= ' ' || c == ',' || c == '-') {
                if (i < limit) {
                    si = s.charAt(i);
                    if (c == '-' && '0' <= si && si <= '9') {
                        prevc = c;
                    }
                }
                continue;
            }
            if (c == '(') { /* comments) */
                int depth = 1;
                while (i < limit) {
                    c = s.charAt(i);
                    i++;
                    if (c == '(')
                        depth++;
                    else if (c == ')')
                        if (--depth <= 0)
                            break;
                }
                continue;
            }
            if ('0' <= c && c <= '9') {
                n = c - '0';
                while (i < limit && '0' <= (c = s.charAt(i)) && c <= '9') {
                    n = n * 10 + c - '0';
                    i++;
                }

                /* allow TZA before the year, so
                 * 'Wed Nov 05 21:49:11 GMT-0800 1997'
                 * works */

                /* uses of seenplusminus allow : in TZA, so Java
                 * no-timezone style of GMT+4:30 works
                 */
                if ((prevc == '+' || prevc == '-')/*  && year>=0 */) {
                    /* make ':' case below change tzoffset */
                    seenplusminus = true;

                    /* offset */
                    if (n < 24)
                        n = n * 60; /* EG. "GMT-3" */
                    else
                        n = n % 100 + n / 100 * 60; /* eg "GMT-0430" */
                    if (prevc == '+')       /* plus means east of GMT */
                        n = -n;
                    if (tzoffset != 0 && tzoffset != -1)
                        return ScriptRuntime.NaN;
                    tzoffset = n;
                } else if (n >= 70  ||
                           (prevc == '/' && mon >= 0 && mday >= 0
                            && year < 0))
                {
                    if (year >= 0)
                        return ScriptRuntime.NaN;
                    else if (c <= ' ' || c == ',' || c == '/' || i >= limit)
                        year = n < 100 ? n + 1900 : n;
                    else
                        return ScriptRuntime.NaN;
                } else if (c == ':') {
                    if (hour < 0)
                        hour = /*byte*/ n;
                    else if (min < 0)
                        min = /*byte*/ n;
                    else
                        return ScriptRuntime.NaN;
                } else if (c == '/') {
                    if (mon < 0)
                        mon = /*byte*/ n-1;
                    else if (mday < 0)
                        mday = /*byte*/ n;
                    else
                        return ScriptRuntime.NaN;
                } else if (i < limit && c != ',' && c > ' ' && c != '-') {
                    return ScriptRuntime.NaN;
                } else if (seenplusminus && n < 60) {  /* handle GMT-3:30 */
                    if (tzoffset < 0)
                        tzoffset -= n;
                    else
                        tzoffset += n;
                } else if (hour >= 0 && min < 0) {
                    min = /*byte*/ n;
                } else if (min >= 0 && sec < 0) {
                    sec = /*byte*/ n;
                } else if (mday < 0) {
                    mday = /*byte*/ n;
                } else {
                    return ScriptRuntime.NaN;
                }
                prevc = 0;
            } else if (c == '/' || c == ':' || c == '+' || c == '-') {
                prevc = c;
            } else {
                int st = i - 1;
                while (i < limit) {
                    c = s.charAt(i);
                    if (!(('A' <= c && c <= 'Z') || ('a' <= c && c <= 'z')))
                        break;
                    i++;
                }
                int letterCount = i - st;
                if (letterCount < 2)
                    return ScriptRuntime.NaN;
               /*
                * Use ported code from jsdate.c rather than the locale-specific
                * date-parsing code from Java, to keep js and rhino consistent.
                * Is this the right strategy?
                */
                String wtb = "am;pm;"
                            +"monday;tuesday;wednesday;thursday;friday;"
                            +"saturday;sunday;"
                            +"january;february;march;april;may;june;"
                            +"july;august;september;october;november;december;"
                            +"gmt;ut;utc;est;edt;cst;cdt;mst;mdt;pst;pdt;";
                int index = 0;
                for (int wtbOffset = 0; ;) {
                    int wtbNext = wtb.indexOf(';', wtbOffset);
                    if (wtbNext < 0)
                        return ScriptRuntime.NaN;
                    if (wtb.regionMatches(true, wtbOffset, s, st, letterCount))
                        break;
                    wtbOffset = wtbNext + 1;
                    ++index;
                }
                if (index < 2) {
                    /*
                     * AM/PM. Count 12:30 AM as 00:30, 12:30 PM as
                     * 12:30, instead of blindly adding 12 if PM.
                     */
                    if (hour > 12 || hour < 0) {
                        return ScriptRuntime.NaN;
                    } else if (index == 0) {
                        // AM
                        if (hour == 12)
                            hour = 0;
                    } else {
                        // PM
                        if (hour != 12)
                            hour += 12;
                    }
                } else if ((index -= 2) < 7) {
                    // ignore week days
                } else if ((index -= 7) < 12) {
                    // month
                    if (mon < 0) {
                        mon = index;
                    } else {
                        return ScriptRuntime.NaN;
                    }
                } else {
                    index -= 12;
                    // timezones
                    switch (index) {
                      case 0 /* gmt */: tzoffset = 0; break;
                      case 1 /* ut */:  tzoffset = 0; break;
                      case 2 /* utc */: tzoffset = 0; break;
                      case 3 /* est */: tzoffset = 5 * 60; break;
                      case 4 /* edt */: tzoffset = 4 * 60; break;
                      case 5 /* cst */: tzoffset = 6 * 60; break;
                      case 6 /* cdt */: tzoffset = 5 * 60; break;
                      case 7 /* mst */: tzoffset = 7 * 60; break;
                      case 8 /* mdt */: tzoffset = 6 * 60; break;
                      case 9 /* pst */: tzoffset = 8 * 60; break;
                      case 10 /* pdt */:tzoffset = 7 * 60; break;
                      default: Kit.codeBug();
                    }
                }
            }
        }
        if (year < 0 || mon < 0 || mday < 0)
            return ScriptRuntime.NaN;
        if (sec < 0)
            sec = 0;
        if (min < 0)
            min = 0;
        if (hour < 0)
            hour = 0;

        double msec = date_msecFromDate(year, mon, mday, hour, min, sec, 0);
        if (tzoffset == -1) { /* no time zone specified, have to use local */
            return internalUTC(msec);
        } else {
            return msec + tzoffset * msPerMinute;
        }
    }

    private static String date_format(double t, int methodId) {
        if (t != t)
            return js_NaN_date_str;

        StringBuffer result = new StringBuffer(60);
        double local = LocalTime(t);

        /* Tue Oct 31 09:41:40 GMT-0800 (PST) 2000 */
        /* Tue Oct 31 2000 */
        /* 09:41:40 GMT-0800 (PST) */

        if (methodId != Id_toTimeString) {
            appendWeekDayName(result, WeekDay(local));
            result.append(' ');
            appendMonthName(result, MonthFromTime(local));
            result.append(' ');
            append0PaddedUint(result, DateFromTime(local), 2);
            result.append(' ');
            int year = YearFromTime(local);
            if (year < 0) {
                result.append('-');
                year = -year;
            }
            append0PaddedUint(result, year, 4);
            if (methodId != Id_toDateString)
                result.append(' ');
        }

        if (methodId != Id_toDateString) {
            append0PaddedUint(result, HourFromTime(local), 2);
            result.append(':');
            append0PaddedUint(result, MinFromTime(local), 2);
            result.append(':');
            append0PaddedUint(result, SecFromTime(local), 2);

            // offset from GMT in minutes.  The offset includes daylight
            // savings, if it applies.
            int minutes = (int) Math.floor((LocalTZA + DaylightSavingTA(t))
                                           / msPerMinute);
            // map 510 minutes to 0830 hours
            int offset = (minutes / 60) * 100 + minutes % 60;
            if (offset > 0) {
                result.append(" GMT+");
            } else {
                result.append(" GMT-");
                offset = -offset;
            }
            append0PaddedUint(result, offset, 4);

            if (timeZoneFormatter == null)
                timeZoneFormatter = new java.text.SimpleDateFormat("zzz");

            if (timeZoneFormatter != null) {
                result.append(" (");
                java.util.Date date = new Date((long) t);
                result.append(timeZoneFormatter.format(date));
                result.append(')');
            }
        }
        return result.toString();
    }

    /* the javascript constructor */
    private static Object jsConstructor(Object[] args)
    {
        NativeDate obj = new NativeDate();

        // if called as a constructor with no args,
        // return a new Date with the current time.
        if (args.length == 0) {
            obj.date = Now();
            return obj;
        }

        // if called with just one arg -
        if (args.length == 1) {
            Object arg0 = args[0];
            if (arg0 instanceof Scriptable)
                arg0 = ((Scriptable) arg0).getDefaultValue(null);
            double date;
            if (arg0 instanceof String) {
                // it's a string; parse it.
                date = date_parseString((String)arg0);
            } else {
                // if it's not a string, use it as a millisecond date
                date = ScriptRuntime.toNumber(arg0);
            }
            obj.date = TimeClip(date);
            return obj;
        }

        // multiple arguments; year, month, day etc.
        double array[] = new double[MAXARGS];
        int loop;
        double d;

        for (loop = 0; loop < MAXARGS; loop++) {
            if (loop < args.length) {
                d = ScriptRuntime.toNumber(args[loop]);

                if (d != d || Double.isInfinite(d)) {
                    obj.date = ScriptRuntime.NaN;
                    return obj;
                }
                array[loop] = ScriptRuntime.toInteger(args[loop]);
            } else {
                array[loop] = 0;
            }
        }

        /* adjust 2-digit years into the 20th century */
        if (array[0] >= 0 && array[0] <= 99)
            array[0] += 1900;

        /* if we got a 0 for 'date' (which is out of range)
         * pretend it's a 1 */
        if (array[2] < 1)
            array[2] = 1;

        double day = MakeDay(array[0], array[1], array[2]);
        double time = MakeTime(array[3], array[4], array[5], array[6]);
        time = MakeDate(day, time);
        time = internalUTC(time);
        obj.date = TimeClip(time);

        return obj;
    }

    /* constants for toString, toUTCString */
    private static final String js_NaN_date_str = "Invalid Date";

    private static String toLocale_helper(double t, int methodId)
    {
        if (t != t)
            return js_NaN_date_str;

        java.text.DateFormat formatter;
        switch (methodId) {
          case Id_toLocaleString:
            if (localeDateTimeFormatter == null) {
                localeDateTimeFormatter
                    = DateFormat.getDateTimeInstance(DateFormat.LONG,
                                                     DateFormat.LONG);
            }
            formatter = localeDateTimeFormatter;
            break;
          case Id_toLocaleTimeString:
            if (localeTimeFormatter == null) {
                localeTimeFormatter
                    = DateFormat.getTimeInstance(DateFormat.LONG);
            }
            formatter = localeTimeFormatter;
            break;
          case Id_toLocaleDateString:
            if (localeDateFormatter == null) {
                localeDateFormatter
                    = DateFormat.getDateInstance(DateFormat.LONG);
            }
            formatter = localeDateFormatter;
            break;
          default: formatter = null; // unreachable
        }

        return formatter.format(new Date((long) t));
    }

    private static String js_toUTCString(double date) {
        StringBuffer result = new StringBuffer(60);

        appendWeekDayName(result, WeekDay(date));
        result.append(", ");
        append0PaddedUint(result, DateFromTime(date), 2);
        result.append(' ');
        appendMonthName(result, MonthFromTime(date));
        result.append(' ');
        int year = YearFromTime(date);
        if (year < 0) {
            result.append('-'); year = -year;
        }
        append0PaddedUint(result, year, 4);
        result.append(' ');
        append0PaddedUint(result, HourFromTime(date), 2);
        result.append(':');
        append0PaddedUint(result, MinFromTime(date), 2);
        result.append(':');
        append0PaddedUint(result, SecFromTime(date), 2);
        result.append(" GMT");
        return result.toString();
    }

    private static void append0PaddedUint(StringBuffer sb, int i, int minWidth)
    {
        if (i < 0) Kit.codeBug();
        int scale = 1;
        --minWidth;
        if (i >= 10) {
            if (i < 1000 * 1000 * 1000) {
                for (;;) {
                    int newScale = scale * 10;
                    if (i < newScale) { break; }
                    --minWidth;
                    scale = newScale;
                }
            } else {
                // Separated case not to check against 10 * 10^9 overflow
                minWidth -= 9;
                scale = 1000 * 1000 * 1000;
            }
        }
        while (minWidth > 0) {
            sb.append('0');
            --minWidth;
        }
        while (scale != 1) {
            sb.append((char)('0' + (i / scale)));
            i %= scale;
            scale /= 10;
        }
        sb.append((char)('0' + i));
    }

    private static void appendMonthName(StringBuffer sb, int index)
    {
        // Take advantage of the fact that all month abbreviations
        // have the same length to minimize amount of strings runtime has
        // to keep in memory
        String months = "Jan"+"Feb"+"Mar"+"Apr"+"May"+"Jun"
                       +"Jul"+"Aug"+"Sep"+"Oct"+"Nov"+"Dec";
        index *= 3;
        for (int i = 0; i != 3; ++i) {
            sb.append(months.charAt(index + i));
        }
    }

    private static void appendWeekDayName(StringBuffer sb, int index)
    {
        String days = "Sun"+"Mon"+"Tue"+"Wed"+"Thu"+"Fri"+"Sat";
        index *= 3;
        for (int i = 0; i != 3; ++i) {
            sb.append(days.charAt(index + i));
        }
    }

    private double makeTime(Object[] args, int methodId)
    {
        int maxargs;
        boolean local = true;
        switch (methodId) {
          case Id_setUTCMilliseconds:
              local = false;
            // fallthrough
          case Id_setMilliseconds:
            maxargs = 1;
            break;

          case Id_setUTCSeconds:
              local = false;
            // fallthrough
          case Id_setSeconds:
            maxargs = 2;
            break;

          case Id_setUTCMinutes:
              local = false;
            // fallthrough
          case Id_setMinutes:
            maxargs = 3;
            break;

          case Id_setUTCHours:
              local = false;
            // fallthrough
          case Id_setHours:
            maxargs = 4;
            break;

          default:
              Kit.codeBug();
            maxargs = 0;
        }

        int i;
        double conv[] = new double[4];
        double hour, min, sec, msec;
        double lorutime; /* Local or UTC version of date */

        double time;
        double result;

        double date = this.date;

        /* just return NaN if the date is already NaN */
        if (date != date)
            return date;

        /* Satisfy the ECMA rule that if a function is called with
         * fewer arguments than the specified formal arguments, the
         * remaining arguments are set to undefined.  Seems like all
         * the Date.setWhatever functions in ECMA are only varargs
         * beyond the first argument; this should be set to undefined
         * if it's not given.  This means that "d = new Date();
         * d.setMilliseconds()" returns NaN.  Blech.
         */
        if (args.length == 0)
            args = ScriptRuntime.padArguments(args, 1);

        for (i = 0; i < args.length && i < maxargs; i++) {
            conv[i] = ScriptRuntime.toNumber(args[i]);

            // limit checks that happen in MakeTime in ECMA.
            if (conv[i] != conv[i] || Double.isInfinite(conv[i])) {
                this.date = ScriptRuntime.NaN;
                return this.date;
            }
            conv[i] = ScriptRuntime.toInteger(conv[i]);
        }

        if (local)
            lorutime = LocalTime(date);
        else
            lorutime = date;

        i = 0;
        int stop = args.length;

        if (maxargs >= 4 && i < stop)
            hour = conv[i++];
        else
            hour = HourFromTime(lorutime);

        if (maxargs >= 3 && i < stop)
            min = conv[i++];
        else
            min = MinFromTime(lorutime);

        if (maxargs >= 2 && i < stop)
            sec = conv[i++];
        else
            sec = SecFromTime(lorutime);

        if (maxargs >= 1 && i < stop)
            msec = conv[i++];
        else
            msec = msFromTime(lorutime);

        time = MakeTime(hour, min, sec, msec);
        result = MakeDate(Day(lorutime), time);

        if (local)
            result = internalUTC(result);
        date = TimeClip(result);

        this.date = date;
        return date;
    }

    private double makeDate(Object[] args, int methodId)
    {
        int maxargs;
        boolean local = true;
        switch (methodId) {
          case Id_setUTCDate:
              local = false;
            // fallthrough
          case Id_setDate:
              maxargs = 1;
            break;

          case Id_setUTCMonth:
              local = false;
            // fallthrough
          case Id_setMonth:
              maxargs = 2;
            break;

          case Id_setUTCFullYear:
              local = false;
            // fallthrough
          case Id_setFullYear:
              maxargs = 3;
            break;

          default:
              Kit.codeBug();
            maxargs = 0;
        }

        int i;
        double conv[] = new double[3];
        double year, month, day;
        double lorutime; /* local or UTC version of date */
        double result;

        double date = this.date;

        /* See arg padding comment in makeTime.*/
        if (args.length == 0)
            args = ScriptRuntime.padArguments(args, 1);

        for (i = 0; i < args.length && i < maxargs; i++) {
            conv[i] = ScriptRuntime.toNumber(args[i]);

            // limit checks that happen in MakeDate in ECMA.
            if (conv[i] != conv[i] || Double.isInfinite(conv[i])) {
                this.date = ScriptRuntime.NaN;
                return this.date;
            }
            conv[i] = ScriptRuntime.toInteger(conv[i]);
        }

        /* return NaN if date is NaN and we're not setting the year,
         * If we are, use 0 as the time. */
        if (date != date) {
            if (args.length < 3) {
                return ScriptRuntime.NaN;
            } else {
                lorutime = 0;
            }
        } else {
            if (local)
                lorutime = LocalTime(date);
            else
                lorutime = date;
        }

        i = 0;
        int stop = args.length;

        if (maxargs >= 3 && i < stop)
            year = conv[i++];
        else
            year = YearFromTime(lorutime);

        if (maxargs >= 2 && i < stop)
            month = conv[i++];
        else
            month = MonthFromTime(lorutime);

        if (maxargs >= 1 && i < stop)
            day = conv[i++];
        else
            day = DateFromTime(lorutime);

        day = MakeDay(year, month, day); /* day within year */
        result = MakeDate(day, TimeWithinDay(lorutime));

        if (local)
            result = internalUTC(result);

        date = TimeClip(result);

        this.date = date;
        return date;
    }

    private double js_setYear(double year) {
        double day, result;
        if (year != year || Double.isInfinite(year)) {
            this.date = ScriptRuntime.NaN;
            return this.date;
        }

        if (this.date != this.date) {
            this.date = 0;
        } else {
            this.date = LocalTime(this.date);
        }

        if (year >= 0 && year <= 99)
            year += 1900;

        day = MakeDay(year, MonthFromTime(this.date), DateFromTime(this.date));
        result = MakeDate(day, TimeWithinDay(this.date));
        result = internalUTC(result);

        this.date = TimeClip(result);
        return this.date;
    }

    protected String getIdName(int id) {
        if (prototypeFlag) {
            switch (id) {
                case ConstructorId_UTC:     return "UTC";
                case ConstructorId_parse:   return "parse";
                case Id_constructor:        return "constructor";
                case Id_toString:           return "toString";
                case Id_toTimeString:       return "toTimeString";
                case Id_toDateString:       return "toDateString";
                case Id_toLocaleString:     return "toLocaleString";
                case Id_toLocaleTimeString: return "toLocaleTimeString";
                case Id_toLocaleDateString: return "toLocaleDateString";
                case Id_toUTCString:        return "toUTCString";
                case Id_toSource:           return "toSource";
                case Id_valueOf:            return "valueOf";
                case Id_getTime:            return "getTime";
                case Id_getYear:            return "getYear";
                case Id_getFullYear:        return "getFullYear";
                case Id_getUTCFullYear:     return "getUTCFullYear";
                case Id_getMonth:           return "getMonth";
                case Id_getUTCMonth:        return "getUTCMonth";
                case Id_getDate:            return "getDate";
                case Id_getUTCDate:         return "getUTCDate";
                case Id_getDay:             return "getDay";
                case Id_getUTCDay:          return "getUTCDay";
                case Id_getHours:           return "getHours";
                case Id_getUTCHours:        return "getUTCHours";
                case Id_getMinutes:         return "getMinutes";
                case Id_getUTCMinutes:      return "getUTCMinutes";
                case Id_getSeconds:         return "getSeconds";
                case Id_getUTCSeconds:      return "getUTCSeconds";
                case Id_getMilliseconds:    return "getMilliseconds";
                case Id_getUTCMilliseconds: return "getUTCMilliseconds";
                case Id_getTimezoneOffset:  return "getTimezoneOffset";
                case Id_setTime:            return "setTime";
                case Id_setMilliseconds:    return "setMilliseconds";
                case Id_setUTCMilliseconds: return "setUTCMilliseconds";
                case Id_setSeconds:         return "setSeconds";
                case Id_setUTCSeconds:      return "setUTCSeconds";
                case Id_setMinutes:         return "setMinutes";
                case Id_setUTCMinutes:      return "setUTCMinutes";
                case Id_setHours:           return "setHours";
                case Id_setUTCHours:        return "setUTCHours";
                case Id_setDate:            return "setDate";
                case Id_setUTCDate:         return "setUTCDate";
                case Id_setMonth:           return "setMonth";
                case Id_setUTCMonth:        return "setUTCMonth";
                case Id_setFullYear:        return "setFullYear";
                case Id_setUTCFullYear:     return "setUTCFullYear";
                case Id_setYear:            return "setYear";
            }
        }
        return null;
    }

// #string_id_map#

    protected int mapNameToId(String s) {
        if (!prototypeFlag) { return 0; }
        int id;
// #generated# Last update: 2004-03-17 13:33:23 CET
        L0: { id = 0; String X = null; int c;
            L: switch (s.length()) {
            case 6: X="getDay";id=Id_getDay; break L;
            case 7: switch (s.charAt(3)) {
                case 'D': c=s.charAt(0);
                    if (c=='g') { X="getDate";id=Id_getDate; }
                    else if (c=='s') { X="setDate";id=Id_setDate; }
                    break L;
                case 'T': c=s.charAt(0);
                    if (c=='g') { X="getTime";id=Id_getTime; }
                    else if (c=='s') { X="setTime";id=Id_setTime; }
                    break L;
                case 'Y': c=s.charAt(0);
                    if (c=='g') { X="getYear";id=Id_getYear; }
                    else if (c=='s') { X="setYear";id=Id_setYear; }
                    break L;
                case 'u': X="valueOf";id=Id_valueOf; break L;
                } break L;
            case 8: switch (s.charAt(3)) {
                case 'H': c=s.charAt(0);
                    if (c=='g') { X="getHours";id=Id_getHours; }
                    else if (c=='s') { X="setHours";id=Id_setHours; }
                    break L;
                case 'M': c=s.charAt(0);
                    if (c=='g') { X="getMonth";id=Id_getMonth; }
                    else if (c=='s') { X="setMonth";id=Id_setMonth; }
                    break L;
                case 'o': X="toSource";id=Id_toSource; break L;
                case 't': X="toString";id=Id_toString; break L;
                } break L;
            case 9: X="getUTCDay";id=Id_getUTCDay; break L;
            case 10: c=s.charAt(3);
                if (c=='M') {
                    c=s.charAt(0);
                    if (c=='g') { X="getMinutes";id=Id_getMinutes; }
                    else if (c=='s') { X="setMinutes";id=Id_setMinutes; }
                }
                else if (c=='S') {
                    c=s.charAt(0);
                    if (c=='g') { X="getSeconds";id=Id_getSeconds; }
                    else if (c=='s') { X="setSeconds";id=Id_setSeconds; }
                }
                else if (c=='U') {
                    c=s.charAt(0);
                    if (c=='g') { X="getUTCDate";id=Id_getUTCDate; }
                    else if (c=='s') { X="setUTCDate";id=Id_setUTCDate; }
                }
                break L;
            case 11: switch (s.charAt(3)) {
                case 'F': c=s.charAt(0);
                    if (c=='g') { X="getFullYear";id=Id_getFullYear; }
                    else if (c=='s') { X="setFullYear";id=Id_setFullYear; }
                    break L;
                case 'M': X="toGMTString";id=Id_toGMTString; break L;
                case 'T': X="toUTCString";id=Id_toUTCString; break L;
                case 'U': c=s.charAt(0);
                    if (c=='g') {
                        c=s.charAt(9);
                        if (c=='r') { X="getUTCHours";id=Id_getUTCHours; }
                        else if (c=='t') { X="getUTCMonth";id=Id_getUTCMonth; }
                    }
                    else if (c=='s') {
                        c=s.charAt(9);
                        if (c=='r') { X="setUTCHours";id=Id_setUTCHours; }
                        else if (c=='t') { X="setUTCMonth";id=Id_setUTCMonth; }
                    }
                    break L;
                case 's': X="constructor";id=Id_constructor; break L;
                } break L;
            case 12: c=s.charAt(2);
                if (c=='D') { X="toDateString";id=Id_toDateString; }
                else if (c=='T') { X="toTimeString";id=Id_toTimeString; }
                break L;
            case 13: c=s.charAt(0);
                if (c=='g') {
                    c=s.charAt(6);
                    if (c=='M') { X="getUTCMinutes";id=Id_getUTCMinutes; }
                    else if (c=='S') { X="getUTCSeconds";id=Id_getUTCSeconds; }
                }
                else if (c=='s') {
                    c=s.charAt(6);
                    if (c=='M') { X="setUTCMinutes";id=Id_setUTCMinutes; }
                    else if (c=='S') { X="setUTCSeconds";id=Id_setUTCSeconds; }
                }
                break L;
            case 14: c=s.charAt(0);
                if (c=='g') { X="getUTCFullYear";id=Id_getUTCFullYear; }
                else if (c=='s') { X="setUTCFullYear";id=Id_setUTCFullYear; }
                else if (c=='t') { X="toLocaleString";id=Id_toLocaleString; }
                break L;
            case 15: c=s.charAt(0);
                if (c=='g') { X="getMilliseconds";id=Id_getMilliseconds; }
                else if (c=='s') { X="setMilliseconds";id=Id_setMilliseconds; }
                break L;
            case 17: X="getTimezoneOffset";id=Id_getTimezoneOffset; break L;
            case 18: c=s.charAt(0);
                if (c=='g') { X="getUTCMilliseconds";id=Id_getUTCMilliseconds; }
                else if (c=='s') { X="setUTCMilliseconds";id=Id_setUTCMilliseconds; }
                else if (c=='t') {
                    c=s.charAt(8);
                    if (c=='D') { X="toLocaleDateString";id=Id_toLocaleDateString; }
                    else if (c=='T') { X="toLocaleTimeString";id=Id_toLocaleTimeString; }
                }
                break L;
            }
            if (X!=null && X!=s && !X.equals(s)) id = 0;
        }
// #/generated#
        return id;
    }

    private static final int
        ConstructorId_UTC       = -2,
        ConstructorId_parse     = -1,

        Id_constructor          =  1,
        Id_toString             =  2,
        Id_toTimeString         =  3,
        Id_toDateString         =  4,
        Id_toLocaleString       =  5,
        Id_toLocaleTimeString   =  6,
        Id_toLocaleDateString   =  7,
        Id_toUTCString          =  8,
        Id_toSource             =  9,
        Id_valueOf              = 10,
        Id_getTime              = 11,
        Id_getYear              = 12,
        Id_getFullYear          = 13,
        Id_getUTCFullYear       = 14,
        Id_getMonth             = 15,
        Id_getUTCMonth          = 16,
        Id_getDate              = 17,
        Id_getUTCDate           = 18,
        Id_getDay               = 19,
        Id_getUTCDay            = 20,
        Id_getHours             = 21,
        Id_getUTCHours          = 22,
        Id_getMinutes           = 23,
        Id_getUTCMinutes        = 24,
        Id_getSeconds           = 25,
        Id_getUTCSeconds        = 26,
        Id_getMilliseconds      = 27,
        Id_getUTCMilliseconds   = 28,
        Id_getTimezoneOffset    = 29,
        Id_setTime              = 30,
        Id_setMilliseconds      = 31,
        Id_setUTCMilliseconds   = 32,
        Id_setSeconds           = 33,
        Id_setUTCSeconds        = 34,
        Id_setMinutes           = 35,
        Id_setUTCMinutes        = 36,
        Id_setHours             = 37,
        Id_setUTCHours          = 38,
        Id_setDate              = 39,
        Id_setUTCDate           = 40,
        Id_setMonth             = 41,
        Id_setUTCMonth          = 42,
        Id_setFullYear          = 43,
        Id_setUTCFullYear       = 44,
        Id_setYear              = 45,

        MAX_PROTOTYPE_ID        = 45;

    private static final int
        Id_toGMTString  =  Id_toUTCString; // Alias, see Ecma B.2.6
// #/string_id_map#

    /* cached values */
    private static java.util.TimeZone thisTimeZone;
    private static double LocalTZA;
    private static java.text.DateFormat timeZoneFormatter;
    private static java.text.DateFormat localeDateTimeFormatter;
    private static java.text.DateFormat localeDateFormatter;
    private static java.text.DateFormat localeTimeFormatter;

    private double date;

    private boolean prototypeFlag;
}


