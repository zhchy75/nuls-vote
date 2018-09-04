package io.nuls.vote.contract.func;

import io.nuls.contract.sdk.Address;
import io.nuls.contract.sdk.Block;
import io.nuls.contract.sdk.Msg;
import io.nuls.vote.contract.event.VoteCreateEvent;
import io.nuls.vote.contract.event.VoteEvent;
import io.nuls.vote.contract.event.VoteInitEvent;
import io.nuls.vote.contract.model.VoteConfig;
import io.nuls.vote.contract.model.VoteEntity;
import io.nuls.vote.contract.model.VoteItem;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.nuls.contract.sdk.Utils.emit;
import static io.nuls.contract.sdk.Utils.require;

public class BaseVote implements VoteInterface {

    private BigInteger minRecognizance;

    protected Map<Long, VoteEntity> votes = new HashMap<Long, VoteEntity>();
    protected Map<Long, Map<Address, List<Long>>> voteRecords = new HashMap<Long, Map<Address, List<Long>>>();

    public BaseVote(BigInteger minRecognizance) {
        require(minRecognizance.compareTo(BigInteger.valueOf(0)) > 0);
        this.minRecognizance = minRecognizance;
    }

    public VoteEntity create(String title, String desc, String[] items) {

        require(items != null && items.length > 0);
        require(title != null);
        require(desc != null);

        BigInteger value = Msg.value();
        require(value.compareTo(minRecognizance) >= 0);

        Long voteId = Long.valueOf(votes.size() + 1);

        VoteEntity voteEntity = new VoteEntity();

        voteEntity.setId(voteId);
        voteEntity.setTitle(title);
        voteEntity.setDesc(desc);
        voteEntity.setRecognizance(value);

        List<VoteItem> itemList = new ArrayList<VoteItem>();

        for(int itemId = 0 ; itemId < items.length ; itemId++) {
            VoteItem item = new VoteItem();
            item.setId((long) itemId + 1);
            item.setContent(items[itemId]);
            itemList.add(item);
        }

        voteEntity.setItems(itemList);
        voteEntity.setStatus(VoteStatus.STATUS_WAIT_INIT);
        voteEntity.setOwner(Msg.sender());

        votes.put(voteId, voteEntity);

        emit(new VoteCreateEvent(voteId, title, desc, itemList));

        return voteEntity;
    }

    public boolean init(long voteId, VoteConfig config) {

        require(voteId > 0L);
        require(config != null);

        onlyOwner(voteId);

        require(config.check());

        VoteEntity voteEntity = votes.get(voteId);

        require(voteEntity != null);

        require(voteEntity.getStatus() == VoteStatus.STATUS_WAIT_INIT);

        List<VoteItem> items = voteEntity.getItems();
        require(items != null && items.size() > 0);

        if(config.isMultipleSelect()) {
            require(config.getMaxSelectCount() <= items.size());
        }

        voteEntity.setConfig(config);
        voteEntity.setStatus(VoteStatus.STATUS_WAIT_VOTE);

        emit(new VoteInitEvent(voteId, config));

        return true;
    }

    public boolean vote(long voteId, long[] itemIds) {

        require(canVote(voteId));
        require(itemIds != null && itemIds.length > 0);

        VoteEntity voteEntity = votes.get(voteId);
        VoteConfig config = voteEntity.getConfig();

        if(config.isMultipleSelect()) {
            require(itemIds.length <= config.getMaxSelectCount());
        }
        if(!config.isMultipleSelect()) {
            require(itemIds.length == 1);
        }

        List<VoteItem> items = voteEntity.getItems();
        List<Long> itemIdList = new ArrayList<Long>();

        for(int i = 0 ; i < itemIds.length ; i++) {
            Long itemId = itemIds[i];
            boolean hasExist = false;

            for(int m = 0 ; m < items.size() ; m++) {
                VoteItem voteItem = items.get(m);
                if(voteItem.getId().equals(itemId)) {
                    hasExist = true;
                    break;
                }
            }

            if(!hasExist) {
                require(false);
                return false;
            }
            itemIdList.add(itemId);
        }

        Map<Address, List<Long>> records = voteRecords.get(voteId);
        if(records == null) {
            records = new HashMap<Address, List<Long>>();
            voteRecords.put(voteId, records);
        }

        if(!voteEntity.getConfig().isVoteCanModify()) {
            require(records.get(Msg.sender()) == null);
        }

        records.put(Msg.sender(), itemIdList);

        emit(new VoteEvent(voteId, itemIdList));

        return true;
    }

    public boolean redemption(long voteId) {

        require(voteId > 0L);

        //onlyOwner(voteId);

        VoteEntity voteEntity = votes.get(voteId);

        require(voteEntity != null);

        if(voteEntity.getStatus() != VoteStatus.STATUS_CLOSE) {
            require(!canVote(voteId));
        }

        require(voteEntity.getStatus() == VoteStatus.STATUS_CLOSE);
        require(voteEntity.getRecognizance().compareTo(BigInteger.ZERO) > 0);

        BigInteger balance = Msg.address().balance();
        require(balance.compareTo(voteEntity.getRecognizance()) >= 0);


        // return amount
        voteEntity.getOwner().transfer(voteEntity.getRecognizance());

        voteEntity.setRecognizance(BigInteger.ZERO);

        return true;
    }

    public boolean canVote(long voteId) {

        require(voteId > 0L);

        VoteEntity voteEntity = votes.get(voteId);

        if(voteEntity == null) {
            return false;
        }

        VoteConfig config = voteEntity.getConfig();

        if(config == null) {
            return false;
        }

        if(config.getStartTime() > Block.timestamp() || voteEntity.getStatus() == VoteStatus.STATUS_WAIT_INIT) {
            return false;
        } else if(voteEntity.getStatus() == VoteStatus.STATUS_WAIT_VOTE) {
            updateStatus(voteEntity, VoteStatus.STATUS_VOTEING);
        }

        if(voteEntity.getStatus() != VoteStatus.STATUS_VOTEING) {
            return false;
        }

        if(config.getEndTime() < Block.timestamp()) {
            if(voteEntity.getStatus() != VoteStatus.STATUS_CLOSE) {
                updateStatus(voteEntity, VoteStatus.STATUS_CLOSE);
            }
            return false;
        }

        return true;
    }

    public VoteEntity queryVote(long voteId) {

        require(voteId > 0L);

        VoteEntity voteEntity = votes.get(voteId);

        require(voteEntity != null);

        List<VoteItem> items = voteEntity.getItems();
        voteEntity.setItems(items);

        return voteEntity;
    }

    public Map<Address, List<Long>> queryVoteResult(long voteId) {

        require(voteId > 0L);

        VoteEntity voteEntity = votes.get(voteId);

        require(voteEntity != null);

        Map<Address, List<Long>> records = voteRecords.get(voteId);

        return records;
    }

    public boolean queryAddressHasVote(long voteId, Address address) {
        require(voteId > 0L);
        require(address != null);

        VoteEntity voteEntity = votes.get(voteId);

        require(voteEntity != null);

        Map<Address, List<Long>> records = voteRecords.get(voteId);

        require(records != null);

        return records.get(address) != null;
    }

    private void updateStatus(VoteEntity voteEntity, int statusVoteing) {
        voteEntity.setStatus(statusVoteing);
    }

    private void onlyOwner(long voteId) {
        require(voteId > 0L);

        VoteEntity voteEntity = votes.get(voteId);

        require(voteEntity != null);

        require(voteEntity.getOwner().equals(Msg.sender()));
    }
}
