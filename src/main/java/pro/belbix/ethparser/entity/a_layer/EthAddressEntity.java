package pro.belbix.ethparser.entity.a_layer;

import java.io.Serializable;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.NaturalId;

@Entity
@Table(name = "a_eth_address")
@Data
@EqualsAndHashCode(exclude = "idx")
public class EthAddressEntity implements Serializable {

    @Id
    private String address;
    @NaturalId
    @Column(unique = true)
    private Long idx;

    public EthAddressEntity(String address) {
        this.address = address;
    }

    public EthAddressEntity() {
    }
}
