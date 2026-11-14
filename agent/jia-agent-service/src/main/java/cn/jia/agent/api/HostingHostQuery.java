package cn.jia.agent.api;

import cn.jia.agent.hosting.HostingHostService;
import cn.jia.agent.hosting.HostingRentHttp;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

/** Shared schema for the two host cursor-list endpoints, not a second pagination framework. */
record HostingHostQuery(String cursor, int pageSize) {
    static HostingHostQuery parse(HttpServletRequest request) {
        if (request.getContentLengthLong()>0 || request.getHeader("Transfer-Encoding")!=null
                || request.getParameterMap().entrySet().stream().anyMatch(e -> !Set.of("cursor","pageSize").contains(e.getKey()) || e.getValue().length!=1)) throw HostingRentHttp.badRequest();
        String cursor=request.getParameter("cursor"), value=request.getParameter("pageSize");
        HostingHostService.cursor(cursor);
        if (value!=null && !value.matches("[1-9][0-9]{0,2}")) throw HostingRentHttp.badRequest();
        return new HostingHostQuery(cursor,HostingHostService.pageSize(value==null ? 100 : Integer.parseInt(value)));
    }
}
