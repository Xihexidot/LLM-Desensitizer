package com.hdu.apisensitivities.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CollectionTypeUtils {

    private CollectionTypeUtils() {
    }

    public static Map<String, Object> asStringObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }

        for (Object key : map.keySet()) {
            if (!(key instanceof String)) {
                return null;
            }
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> typedMap = (Map<String, Object>) map;
        return typedMap;
    }

    public static List<Object> asObjectList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }

        @SuppressWarnings("unchecked")
        List<Object> typedList = (List<Object>) list;
        return typedList;
    }

    public static List<Map<String, Object>> asStringObjectMapList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }

        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            Map<String, Object> typedMap = asStringObjectMap(item);
            if (typedMap == null) {
                return null;
            }
            result.add(typedMap);
        }

        return result;
    }

    public static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }

        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String str)) {
                return null;
            }
            result.add(str);
        }

        return result;
    }
}
