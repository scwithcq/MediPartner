package com.dsc.medipartner.common.result;

import lombok.Data;

@Data
public class PageQuery {

    private long pageNum = 1;
    private long pageSize = 10;
}
