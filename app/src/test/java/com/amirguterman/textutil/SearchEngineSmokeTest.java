package com.amirguterman.textutil;

import java.util.List;

/** Runs with plain javac/java, without the Android SDK. */
public final class SearchEngineSmokeTest {
    private static void matches(String note, String query, int expected) {
        List<SearchEngine.Hit> hits = SearchEngine.search(note, query);
        if (hits.size() != expected) throw new AssertionError(query + ": expected " + expected + ", got " + hits.size());
    }
    private static void invalid(String query) {
        try { SearchEngine.search("any text 3", query); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected a query error: " + query);
    }
    public static void main(String[] args) {
        matches("some text 1\nsome text 4\nsome text 5", "some text {int.range(1, 4)}", 2);
        matches("some text 1\nsome text 4\nsome text 4", "some text {int.max()}", 2);
        matches("some text -2\nsome text 5", "some text {int.lt(5)}", 1);
        matches("some text 1.2: 5.06\nsome text 4: 5.06", "some text {type.decimal}: {number.in([1, 4, 5.06])}", 1);
        matches("some text 2\nsome text 2.05\nsome text aaa\nsome text 6\nsome text 5", "some text {oneof([2, 2.05, \"aaa\", js((o) => o > 5)])}", 4);
        matches("A12\nA123", "regex:A\\d{2}$", 1);
        matches("some text 12", "some text {int.range(1, 4)}", 0);
        invalid("some text {number.in([1, 4, 5.06]}");
        invalid("{oneof([js((o) => fetch(1))])}");
        System.out.println("SearchEngine smoke tests passed");
    }
}
