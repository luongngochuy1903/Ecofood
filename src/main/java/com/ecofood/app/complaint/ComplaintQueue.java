package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;

public record ComplaintQueue(String id, String name, ProcessingScope scope, QueuePurpose purpose, boolean active) {}
