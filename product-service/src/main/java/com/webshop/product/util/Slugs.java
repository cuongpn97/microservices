package com.webshop.product.util;

import java.text.Normalizer;
import java.util.Locale;

/** Sinh slug URL từ tên sản phẩm tiếng Việt: "Áo thun Đen" → "ao-thun-den". */
public final class Slugs {

    private static final int MAX_LENGTH = 200;

    private Slugs() {
    }

    public static String of(String text) {
        // "đ" không phải chữ có dấu tổ hợp (không tách được bằng NFD) → thay tay trước.
        String s = text.replace('đ', 'd').replace('Đ', 'D');
        // NFD tách "á" thành "a" + dấu sắc (ký tự \p{M}) → bỏ dấu.
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        s = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (s.length() > MAX_LENGTH) {
            s = s.substring(0, MAX_LENGTH).replaceAll("-+$", "");
        }
        return s.isEmpty() ? "san-pham" : s;
    }
}
