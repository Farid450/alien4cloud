package alien4cloud.topology;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Resource;

import alien4cloud.topology.validation.*;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.springframework.stereotype.Service;

import alien4cloud.application.ApplicationEnvironmentService;
import alien4cloud.application.ApplicationService;
import alien4cloud.cloud.CloudService;
import alien4cloud.common.MetaPropertiesService;
import alien4cloud.common.TagService;
import alien4cloud.exception.NotFoundException;
import alien4cloud.model.application.DeploymentSetup;
import alien4cloud.model.cloud.CloudResourceMatcherConfig;
import alien4cloud.model.components.*;
import alien4cloud.model.topology.*;
import alien4cloud.topology.task.*;
import alien4cloud.utils.services.ConstraintPropertyService;

@Service
@Slf4j
public class TopologyValidationService {
    @Resource
    private ApplicationEnvironmentService applicationEnvironmentService;
    @Resource
    private CloudService cloudService;
    @Resource
    private MetaPropertiesService metaPropertiesService;
    @Resource
    private ApplicationService applicationService;
    @Resource
    private TagService tagService;
    @Resource
    private ConstraintPropertyService constraintPropertyService;

    @Resource
    private TopologyPropertiesValidationService topologyPropertiesValidationService;
    @Resource
    private TopologyRequirementBoundsValidationServices topologyRequirementBoundsValidationServices;
    @Resource
    private TopologyAbstractRelationshipValidationService topologyAbstractRelationshipValidationService;
    @Resource
    private TopologyAbstractNodeValidationService topologyAbstractNodeValidationService;
    @Resource
    private HAGroupPolicyValidationService haGroupPolicyValidationService;

    /**
     * Validate if a topology is valid for deployment or not
     *
     * @param topology topology to be validated
     * @param deploymentSetup the deployment setup linked to topology
     * @return the validation result
     */
    public TopologyValidationResult validateTopology(Topology topology, DeploymentSetup deploymentSetup, CloudResourceMatcherConfig matcherConfig) {
        TopologyValidationResult dto = new TopologyValidationResult();
        if (topology.getNodeTemplates() == null || topology.getNodeTemplates().size() < 1) {
            dto.setValid(false);
            return dto;
        }
        // validate abstract relationships
        dto.addToTaskList(topologyAbstractRelationshipValidationService.validateAbstractRelationships(topology));

        // validate abstract node types and find suggestions
        dto.addToTaskList(topologyAbstractNodeValidationService.findReplacementForAbstracts(topology));

        // validate requirements lowerBounds
        dto.addToTaskList(topologyRequirementBoundsValidationServices.validateRequirementsLowerBounds(topology));

        // validate required properties (properties of NodeTemplate, Relationship and Capability)
        // check also CLOUD / ENVIRONMENT meta properties
        List<PropertiesTask> validateProperties = topologyPropertiesValidationService.validateProperties(topology);
        if (hasOnlyPropertiesWarnings(validateProperties)) {
            dto.addToWarningList(validateProperties);
        } else {
            dto.addToTaskList(validateProperties);
        }

        // Validate that HA groups are respected with current configuration
        if (deploymentSetup != null && matcherConfig != null && MapUtils.isNotEmpty(deploymentSetup.getAvailabilityZoneMapping())) {
            dto.addToWarningList(haGroupPolicyValidationService.validateHAGroup(topology, deploymentSetup, matcherConfig));
        }

        dto.setValid(isValidTaskList(dto.getTaskList()));

        return dto;
    }

    private boolean hasOnlyPropertiesWarnings(List<PropertiesTask> properties) {
        if (properties == null) {
            return true;
        }
        for (PropertiesTask task : properties) {
            if (CollectionUtils.isNotEmpty(task.getProperties().get(TaskLevel.REQUIRED))
                    || CollectionUtils.isNotEmpty(task.getProperties().get(TaskLevel.ERROR))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Define if a tasks list is valid or not regarding task types
     * 
     * @param taskList
     * @return
     */
    private boolean isValidTaskList(List<TopologyTask> taskList) {
        if (taskList == null) {
            return true;
        }
        for (TopologyTask task : taskList) {
            // checking SuggestionsTask or RequirementsTask
            if (task instanceof SuggestionsTask || task instanceof RequirementsTask || task instanceof PropertiesTask) {
                return false;
            }
        }
        return true;
    }
}
