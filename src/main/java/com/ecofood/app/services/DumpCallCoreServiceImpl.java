package com.ecofood.app.services;

import com.ecofood.core.services.DumpServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DumpCallCoreServiceImpl {
    private final DumpServiceImpl dumpService;

    public String callDump(){
        return dumpService.printScreen();
    }
}
