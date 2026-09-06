package com.apparat.service;

import com.apparat.dao.ResourceDao;
import com.apparat.exception.ResourceNotFoundException;
import com.apparat.model.Resource;
import com.apparat.model.enums.ResourceStatus;

import java.util.List;

public class ResourceService {

    private final ResourceDao resourceDao;

    public ResourceService(ResourceDao resourceDao) {
        this.resourceDao = resourceDao;
    }

    public List<Resource> browse(String category, ResourceStatus status, String query) {
        return resourceDao.search(category, status, query);
    }

    public Resource get(Long id) {
        return resourceDao.findById(id).orElseThrow(() -> new ResourceNotFoundException("Resource", id));
    }

    public List<Resource> all() {
        return resourceDao.findAll();
    }
}
